package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.BusyStatus
import com.tneff.cyppieagents.model.LifecycleStatus
import com.tneff.cyppieagents.model.StatusFrame
import com.tneff.cyppieagents.model.TerminalStatus
import com.tneff.cyppieagents.model.TokenUsageStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer

/**
 * CYP-846 — the **Compose status-mux consumer**: ONE `/ws/status` socket carries all four content-free read-only
 * status feeds as a discriminated [StatusFrame] union (CYP-840), replacing the four separate sockets the old
 * per-feed `…LiveSource`s opened (lifecycle/token-usage/busy-state/terminal-state → 4 WS ⇒ 1 WS). The Compose twin
 * of web-ts CYP-844; built on the CYP-819 [statusFeed]/[terminalOnRevoke] seam. Cuts the pool WS working-set 14→~11
 * (unblocks CYP-611).
 *
 * **One socket, four projections — stateful (web-ts `liveHub` parity).** The muxed frame stream is [shareIn]'d ONCE
 * and demuxed by an exhaustive `when` ([cache]) into four latest-by-`agentId` caches. Each of the four legacy source
 * interfaces ([AgentLifecycleSource]/[TokenUsageSource]/[BusyStateSource]/[TerminalControlSource]) is a projection
 * that, on subscribe, **replays its current cache** (the snapshot the old per-collector socket re-delivered on every
 * `onStart`) and then streams the live deltas — so a late-mounted window sees the current state at once, exactly as
 * before, and the four ViewModels stay byte-identical (they still call `source.events()`). The feeds are latest-wins
 * / cursor-free, so a re-delivered value is an idempotent upsert (never a duplicate row).
 *
 * **Revoke vs skew — the two failure classes (Backend2 shape authority, CYP-846 addendum):**
 *  - a **1008 (VIOLATED_POLICY)** auth-revoke is **TERMINAL** (via [terminalOnRevoke]) — the shared upstream ends,
 *    [onRevoked] fires the workspace-wide revoke (CYP-819), and the dead token is never re-dialled (no hammer).
 *  - an **undecodable / malformed [StatusFrame]** is **TRANSIENT, never terminal** — the frame is dropped and the
 *    socket LIVES (a content-free feed → a schema violation is a single-drop, not a protocol break). This is the
 *    deliberate opposite of `/ws/comm`'s terminal `ProtocolSkew` (CYP-786) — web-ts F-A5-2/CYP-834 parity. Realised
 *    by the resilient decode below (`getOrNull()` → the frame is skipped by [statusFeed]'s `?.let`, the loop
 *    continues), NOT by any terminal skew path.
 */
class StatusMuxClient(
    private val client: HttpClient,
    private val httpBaseUrl: String,
    private val wsBaseUrl: String,
    private val token: String,
    onRevoked: () -> Unit = {},
    scope: CoroutineScope,
) {
    // Latest-by-agentId caches (immutable-map StateFlows for safe publication across the upstream writer and the
    // per-subscriber readers). Fed ONLY by the shared [frames] upstream's [cache] side-effect below.
    private val lifecycleCache = MutableStateFlow<Map<String, AgentRunStateEvent>>(emptyMap())
    private val tokenCache = MutableStateFlow<Map<String, AgentTokenUsageEvent>>(emptyMap())
    private val busyCache = MutableStateFlow<Map<String, AgentBusyStateEvent>>(emptyMap())
    private val terminalCache = MutableStateFlow<Map<String, AgentTerminalControlEvent>>(emptyMap())

    /**
     * The ONE shared `/ws/status` frame stream. Terminal on 1008 (never re-dials the dead token); transient on a
     * normal/schema-violated close (reconnects). A malformed frame decodes to `null` → [statusFeed] skips it and the
     * socket lives — NOT terminal (the addendum's mandatory transient≠terminal-skew invariant). Each surviving frame
     * updates its cache ([cache]) before fan-out. [SharingStarted.WhileSubscribed]: the socket opens on the first
     * projection subscription and closes 5 s after the last unsubscribes; a terminal 1008 completes the stream (the
     * projections end, no re-subscribe/hammer).
     */
    private val frames: SharedFlow<StatusFrame> =
        client.statusFeed(statusUrl(), label = "status") { text ->
            // CYP-846 addendum: a decode failure on a CONTENT-FREE status frame is TRANSIENT — return null so the
            // frame is dropped and the socket lives; NEVER route it through a terminal skew path (that is
            // /ws/comm-scoped, CYP-786).
            runCatching { CommJson.decodeFromString(StatusFrame.serializer(), text) }.getOrNull()
        }.terminalOnRevoke(onRevoked)
            .onEach { cache(it) }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 0)

    /** The lifecycle source: the REST snapshot (`GET /api/agents`, unchanged) + the muxed live deltas. */
    val lifecycle: AgentLifecycleSource = object : AgentLifecycleSource {
        override suspend fun snapshot(): Map<String, AgentLifecycleState> = fetchSnapshot()
        override fun events(): Flow<AgentLifecycleEvent> = flow {
            lifecycleCache.value.values.forEach { emit(AgentLifecycleEvent(it.agentId, it.runState.toLifecycleState())) }
            emitAll(
                frames.filterIsInstance<LifecycleStatus>()
                    .map { AgentLifecycleEvent(it.event.agentId, it.event.runState.toLifecycleState()) },
            )
        }
    }

    /** The token-usage source — the muxed `TokenUsageStatus` variant, payload verbatim. */
    val tokenUsage: TokenUsageSource = object : TokenUsageSource {
        override fun events(): Flow<AgentTokenUsageEvent> = flow {
            tokenCache.value.values.forEach { emit(it) }
            emitAll(frames.filterIsInstance<TokenUsageStatus>().map { it.event })
        }
    }

    /** The busy source — the muxed `BusyStatus` variant, payload verbatim. */
    val busy: BusyStateSource = object : BusyStateSource {
        override fun events(): Flow<AgentBusyStateEvent> = flow {
            busyCache.value.values.forEach { emit(it) }
            emitAll(frames.filterIsInstance<BusyStatus>().map { it.event })
        }
    }

    /** The terminal-control source — the muxed `TerminalStatus` variant, payload verbatim. */
    val terminal: TerminalControlSource = object : TerminalControlSource {
        override fun events(): Flow<AgentTerminalControlEvent> = flow {
            terminalCache.value.values.forEach { emit(it) }
            emitAll(frames.filterIsInstance<TerminalStatus>().map { it.event })
        }
    }

    /** `GET /api/agents` snapshot (CC1/CYP-179, credentialed) → per-agent [AgentLifecycleState]; fail-closed to empty. */
    private suspend fun fetchSnapshot(): Map<String, AgentLifecycleState> = try {
        val response = client.get("$httpBaseUrl/api/agents") { header(HttpHeaders.Authorization, "Bearer $token") }
        if (!response.status.isSuccess()) {
            emptyMap()
        } else {
            CommJson.decodeFromString(ListSerializer(Agent.serializer()), response.bodyAsText())
                .associate { it.id to it.runState.toLifecycleState() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyMap()
    }

    /**
     * The exhaustive demux (AC#4). Written as a `when` **expression** (the function's Unit body — [MutableStateFlow.update]
     * returns Unit) so the compiler ENFORCES exhaustiveness with **no `else`**: a fifth [StatusFrame] variant fails to
     * compile HERE until it is routed, so no variant can be silently dropped (the Kotlin twin of web-ts `assertNever`).
     */
    private fun cache(frame: StatusFrame): Unit = when (frame) {
        is LifecycleStatus -> lifecycleCache.update { it + (frame.event.agentId to frame.event) }
        is TokenUsageStatus -> tokenCache.update { it + (frame.event.agentId to frame.event) }
        is BusyStatus -> busyCache.update { it + (frame.event.agentId to frame.event) }
        is TerminalStatus -> terminalCache.update { it + (frame.event.agentId to frame.event) }
    }

    private fun statusUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/status?token=$token"
    }
}
