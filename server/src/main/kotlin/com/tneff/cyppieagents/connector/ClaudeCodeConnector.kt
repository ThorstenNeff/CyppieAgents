package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Live Claude-Code connector (Decision D1/D4/D8): spawns one long-lived `claude` process per agent
 * over piped stdio + stream-json, and wires the CYP-9 primitives into an end-to-end path.
 *
 * Security: spawns with [ConnectorDefaults] (pinned flags, tight allowlist, NEVER bypassPermissions
 * — Gate #4); every event is masked before egress (Gate #3); turns are single-flight per session
 * (Gate #5); routing is identity→spoke (Gate #1) and only successful turns post (Gate #6).
 */
class ClaudeCodeConnector(
    private val spawner: ProcessSpawner,
    private val worktreesRoot: File,
    /**
     * Resolves the ANTHROPIC_API_KEY **at spawn time** (S15 / CYP-96), so an operator key change takes
     * effect on the next `open()` (a CYP-73 restart) — there is no boot-frozen value. Backed by the
     * per-project config store (override) → [com.tneff.cyppieagents.boot.Secrets] (env fallback).
     */
    private val resolveApiKey: () -> String?,
    private val registry: SessionRegistry,
    private val router: MediationRouter,
    private val turnQueue: SessionTurnQueue,
    private val scope: CoroutineScope,
    private val allowedTools: List<String> = ConnectorDefaults.DEFAULT_ALLOWED_TOOLS,
    private val permissionMode: String = ConnectorDefaults.DEFAULT_PERMISSION_MODE,
    private val cliCommand: String = "claude",
    // Observability tap (CYP-37). Null = no tapping (keeps tests/older callers working).
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
    /**
     * Resolves the agent's persona at spawn (S14 / CYP-97), written to `CLAUDE.md` in the worktree cwd
     * so Claude-Code auto-discovers it (Doc 05 §5, no `--bare`). Resolved at `open()` so an operator
     * edit takes effect on the next spawn (a CYP-73 restart). Default → no persona / no CLAUDE.md write.
     */
    private val personaOf: (agentId: String) -> String? = { null },
    /**
     * CYP-146 / E1.7 — writes the per-agent `--mcp-config` exposing the in-process Hub MCP server, so the
     * agent has a callable `hub_send` tool (the emission half). Null → no hub tools (dev/tests). The config
     * carries the agent token and is written **out-of-repo, 0600** ([HubMcpConfigWriter]); F1.
     */
    private val mcpConfigWriter: HubMcpConfigWriter? = null,
    /** The agent's bearer token (for the mcp-config auth header). Null → no hub tools for that agent. */
    private val tokenFor: (agentId: String) -> String? = { null },
    /**
     * CYP-163 — a sandbox-ONLY `bypassPermissions` grant. **Null is the production default** → spawns use
     * the sharp [ConnectorDefaults.streamJsonArgs] (Gate #4 fail-closed, never bypass). **Non-null** (the
     * RB1 throwaway-sandbox worker ONLY, human + reviewer signed) → spawns use the **separate, explicit**
     * [ConnectorDefaults.sandboxBypassStreamJsonArgs] override. Must NEVER be set on a product/S8-prod
     * connector — that is the hard, non-leaking invariant the override exists to protect.
     */
    private val sandboxBypassGrant: SandboxBypassGrant? = null,
    /**
     * CYP-167 — durable session-resume. **Null (default) → feature OFF** (no `--resume`, no persistence),
     * so existing callers/tests are unchanged. Non-null (boot) → [open] does read-before-spawn (look up
     * `(projectId, agentId)` → prepend `--resume <id>`) + write-after-init (persist the bound id) + the
     * [ResumingSession] stale-fallback (clear + fresh respawn once if the resumed id is dead).
     */
    private val sessionStore: SessionStore? = null,
    /**
     * Resolves the durable key's projectId at spawn. Boot wires `{ config.projectId }` (boot-frozen,
     * MVP-correct: an agent doesn't change project mid-life — CYP-91 `setActive` is pointer-only). When a
     * live project-switch lands, this would track the agent's owning project instead.
     */
    private val projectIdOf: (agentId: String) -> String = { DEFAULT_PROJECT_ID },
    /** Injectable clock for the entry timestamps (testable). */
    private val clock: () -> Long = System::currentTimeMillis,
) : Connector {

    /**
     * Connector A capability declaration (Doc 10 §4, column A) — **all AVAILABLE**, but NOT blindly:
     * stream-json is the full-fidelity transport and each dimension is coupled to a concrete event/path
     * this connector already produces. Honest declaration is the contract (Doc 10 §3) — if a future
     * change removed a source, the matching dimension would have to drop to LIMITED/UNAVAILABLE here.
     *
     *  - structuredUsage  AVAILABLE ← [ResultEvent.usage] carries per-turn token usage (CYP-36 bander).
     *  - toolGranularity  AVAILABLE ← assistant `tool_use` + user `tool_result` blocks give full
     *                                 `tool.call`/`tool.result` depth (projector CYP-37).
     *  - reliableResult   AVAILABLE ← every turn ends on a [ResultEvent]; this session keys turn-end +
     *                                 mediation handover off it (see [ClaudeCodeSession.start]).
     *  - rateLimitSignal  AVAILABLE ← structured `RateLimitEvent.rate_limit_info` (NOT text-scraped),
     *                                 the source the Warden stall detector reads (S11/Doc 07).
     *  - coordination     AVAILABLE ← mediation: the backend reads the stream + injects turns on stdin
     *                                 ([MediationRouter] + [sendTurn]), so the agent reaches the hub (05 §2).
     */
    override val capabilities: Capabilities = STREAM_JSON_CAPABILITIES

    // E2.1 / CYP-137: Connector A's provider (tool) — Claude (CLI / stream-json realization).
    override val provider: ProviderInfo = ProviderInfo.CLAUDE

    override fun open(agentId: String): ConnectorSession = open(agentId, agentId)

    /** Spawn an agent session whose cwd is [worktreesRoot]/[worktreeName] (Spec §11 isolation). */
    override fun open(agentId: String, worktreeName: String): ConnectorSession {
        val cwd = File(worktreesRoot, worktreeName)
        // CYP-97: place the persona as CLAUDE.md before spawn (auto-discovery). Resolved here so the
        // current (possibly edited) persona is used; null/blank → no file written.
        personaOf(agentId)?.takeIf { it.isNotBlank() }?.let {
            cwd.mkdirs()
            File(cwd, "CLAUDE.md").writeText(it)
        }
        val env = buildMap {
            // D3 / CYP-96: resolve the key AT SPAWN (store override → env fallback) → injected into the
            // session ENV, never a CLI arg. A boot-frozen value would ignore an operator key change.
            resolveApiKey()?.let { put("ANTHROPIC_API_KEY", it) }
            put("HUB_AGENT_ID", agentId)
        }
        // CYP-146: expose `hub_send` by handing the agent an --mcp-config for the in-process Hub MCP server
        // (F1: token-bearing config written 0600 OUT-OF-REPO via [HubMcpConfigWriter], absolute path). The
        // hub tool is pre-approved as ONLY `mcp__hub__hub_send` (F3 tight allowlist, no wildcard, never
        // bypassPermissions); built-in tools stay governed by [permissionMode].
        val mcpConfigPath: String? =
            mcpConfigWriter?.let { w -> tokenFor(agentId)?.let { tok -> w.writeFor(agentId, tok).absolutePath } }
        val effectiveAllowedTools =
            if (mcpConfigPath != null) allowedTools + HubMcpConfigWriter.PREFIXED_SEND_TOOL else allowedTools

        // CYP-167: write-after-init — persist the bound id ONCE `system/init` delivers it (the session
        // invokes this at its bind point). Null store (feature off) → null callback → no persistence.
        val projectId = projectIdOf(agentId)
        val onBound: ((String) -> Unit)? = sessionStore?.let { store ->
            { sessionId -> store.upsert(projectId, agentId, sessionId, clock()) }
        }

        // Spawn ONE session with the given resume id (null = fresh, no `--resume`). Both arg paths emit
        // `--resume` identically; Gate #4 stays sharp (only the grant path emits bypass, CYP-163).
        fun spawnSession(resume: String?): ClaudeCodeSession {
            val baseArgs = sandboxBypassGrant
                ?.let { ConnectorDefaults.sandboxBypassStreamJsonArgs(it, effectiveAllowedTools, resume) }
                ?: ConnectorDefaults.streamJsonArgs(effectiveAllowedTools, permissionMode, resume)
            val command = listOf(cliCommand) + baseArgs +
                (mcpConfigPath?.let { listOf("--mcp-config", it) } ?: emptyList())
            val process = spawner.spawn(command, cwd, env)
            return ClaudeCodeSession(agentId, process, registry, router, turnQueue, scope, recorder, projector, onBound)
        }

        // CYP-167: read-before-spawn. No durable entry (first start, or feature off) ⇒ fresh, no `--resume`,
        // no facade — structurally nothing to resume.
        val resumeId = sessionStore?.find(projectId, agentId)?.sessionId?.takeIf { it.isNotBlank() }
        if (resumeId == null) {
            return spawnSession(null).also { it.start() }
        }
        // An entry exists → attempt `--resume` behind the E4 facade, which self-heals a STALE id: clear the
        // durable entry and respawn fresh exactly once (spike (a)/(b): stale id → is_error while unbound).
        return ResumingSession(
            agentId = agentId,
            scope = scope,
            firstAttempt = spawnSession(resumeId),
            onResumeFailed = { sessionStore.clear(projectId, agentId) },
            respawnFresh = { spawnSession(null) },
        ).also { it.start() }
    }

    companion object {
        /** Connector A (stream-json / API) — full fidelity, all dimensions AVAILABLE (Doc 10 §4). */
        val STREAM_JSON_CAPABILITIES = Capabilities(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.AVAILABLE,
            reliableResult = CapabilityStatus.AVAILABLE,
            rateLimitSignal = CapabilityStatus.AVAILABLE,
            coordination = CapabilityStatus.AVAILABLE,
            kind = ConnectorKind.STREAM_JSON,
        )
    }
}

/** One long-lived stream-json session for a single agent. */
class ClaudeCodeSession(
    override val agentId: String,
    private val process: AgentProcess,
    private val registry: SessionRegistry,
    private val router: MediationRouter,
    private val turnQueue: SessionTurnQueue,
    private val scope: CoroutineScope,
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
    /**
     * CYP-167 — invoked ONCE with the session id at the bind point (after `system/init`), so the durable
     * [SessionStore] is written **after** the id is real (write-after-init). Null → no persistence.
     */
    private val onSessionBound: ((String) -> Unit)? = null,
) : ConnectorSession {

    /** CYP-167 — did this spawn ever bind a session id, or did it die unbound (e.g. a stale `--resume`)? */
    enum class StartupOutcome { BOUND, DIED_UNBOUND }

    private val log = LoggerFactory.getLogger("connector.session")
    private val _events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)
    override val events: Flow<StreamJsonEvent> = _events

    // CYP-167 — completes BOUND at the first system/init bind, or DIED_UNBOUND if the process emits an
    // error result / ends its stdout before ever binding. The [ResumingSession] facade awaits this to
    // decide whether a `--resume` attempt succeeded or must fall back to a fresh respawn.
    private val startupOutcome = CompletableDeferred<StartupOutcome>()
    suspend fun awaitStartupOutcome(): StartupOutcome = startupOutcome.await()

    // correlationId for the in-flight work-run: minted at turn.start, carried to result.final (PO c).
    @Volatile private var currentCorrelationId: String? = null

    // Turn-queue key is the STABLE agentId for the whole session lifetime (Gate #5): it must NOT
    // change at system/init, or a turn injected after init would take a different mutex and race a
    // turn still running on the old key. There is one long-lived session per agent, so agentId is
    // the right, stable serialization key. The session_id is used only for routing (registry).
    @Volatile private var boundSessionId: String? = null
    @Volatile private var pendingTurn: CompletableDeferred<Unit>? = null
    private var readerJob: Job? = null

    fun start() {
        readerJob = scope.launch {
            process.stdoutLines.collect { line ->
                val parsed = runCatching { CommJson.decodeFromString<StreamJsonEvent>(line) }.getOrNull()
                if (parsed == null) {
                    // Version drift / partial line: skip rather than crash the reader (CYP-5 note).
                    if (line.isNotBlank()) log.debug("skipping unparsable line for agent={}", agentId)
                    return@collect
                }
                val masked = EventMasking.mask(parsed) // Gate #3: mask BEFORE any egress

                if (masked is SystemEvent && boundSessionId == null && !masked.sessionId.isNullOrBlank()) {
                    boundSessionId = masked.sessionId
                    registry.bind(masked.sessionId!!, agentId) // Gate #1: authoritative session→agent
                    // CYP-167: write-after-init — persist the durable binding only now the id is real, and
                    // signal the resume facade that this spawn committed (a `--resume` succeeded).
                    onSessionBound?.invoke(masked.sessionId!!)
                    if (!startupOutcome.isCompleted) startupOutcome.complete(StartupOutcome.BOUND)
                }

                // Observability tap (CYP-37): the SINGLE point after masking, before the stream forks
                // to the UI and the hub. Non-blocking (record() is trySend) — no Observer-Effect.
                if (recorder != null && projector != null) {
                    projector.project(agentId, masked.sessionId, currentCorrelationId, masked)
                        .forEach { recorder.record(it) }
                }

                _events.emit(masked) // to /ws/agent (UI), already masked

                // CYP-146 RECONCILE: the stdout `hub_send` extraction (CYP-131 `router.onHubSend`) is
                // RETIRED. The agent now emits via the in-process Hub MCP server (CYP-146), which is the
                // SINGLE router (→ HubMcpTools → postAsAgent). The MCP tool_use surfaces in the stream as
                // the PREFIXED `mcp__hub__hub_send`; routing it here too would DOUBLE-POST, so the collector
                // does not route any tool_use to the hub (tool_use blocks still reach `_events` for the UI).
                // See HubSendExtractionTest.collectorDoesNotRouteMcpHubSend (F2 non-vacuous guard).

                if (masked is ResultEvent) {
                    // CYP-167 stale-resume discriminator (spike-verified, R2): an error result WHILE still
                    // unbound = a dead `--resume` (the spike's `error_during_execution` before any system/init).
                    // R2 is carried by the bind-time complete(BOUND) above: once BOUND, a later error is a no-op
                    // via isCompleted. This boundSessionId==null check is a defensive belt-and-suspenders (an
                    // error is only reachable here while unbound anyway).
                    if (masked.isError && boundSessionId == null && !startupOutcome.isCompleted) {
                        startupOutcome.complete(StartupOutcome.DIED_UNBOUND)
                    }
                    // Turn end: mediate to the hub spoke (Gate #1/#2/#6 live in the router/hub)…
                    runCatching { router.onResult(masked) }
                        .onFailure { log.warn("mediation failed for agent={}: {}", agentId, it.message) }
                    // …and release the single-flight turn (Gate #5).
                    pendingTurn?.complete(Unit)
                    pendingTurn = null
                }
            }
            // Reached only on NORMAL stdout completion (the process exited on its own); a deliberate
            // close() cancels this job instead → record process.exit here. Exit code isn't exposed by
            // AgentProcess, so it's unknown (null) for MVP.
            // CYP-167 secondary net: if stdout ended before ANY bind (a death with no error result either),
            // it's still a failed startup → DIED_UNBOUND so the resume facade can fall back.
            if (!startupOutcome.isCompleted) startupOutcome.complete(StartupOutcome.DIED_UNBOUND)
            if (recorder != null && projector != null) {
                recorder.record(projector.processExit(agentId, boundSessionId, null))
            }
        }
    }

    override suspend fun sendTurn(turn: UserTurn) {
        // Gate #5: serialize on the stable agentId key — hold from injection until this turn's
        // result arrives, so a second injection cannot race a running turn (even across system/init).
        // Single-flight means only the lock holder ever sets pendingTurn, so it can't be overwritten.
        turnQueue.runTurn(agentId) {
            // New work-run: mint a correlationId that the tap carries until this turn's result (PO c).
            val correlationId = UUID.randomUUID().toString()
            currentCorrelationId = correlationId
            if (recorder != null && projector != null) {
                recorder.record(projector.turnStart(agentId, boundSessionId, correlationId))
            }
            val done = CompletableDeferred<Unit>()
            pendingTurn = done
            process.writeLine(turn.toNdjsonLine())
            done.await()
        }
    }

    override fun close() {
        readerJob?.cancel()
        process.destroy()
        boundSessionId?.let { registry.unbind(it) }
        pendingTurn?.cancel()
        if (recorder != null && projector != null) recorder.record(projector.agentStopped(agentId))
    }

    /**
     * Stop the session and **confirm the process is gone** before returning (CYP-73, no zombie). Same
     * teardown as [close] — cancel the reader first so the stdout-completion path doesn't misfire as a
     * crash `process.exit` — then `destroy()` and await actual termination.
     */
    override suspend fun closeAndAwait() {
        readerJob?.cancel()
        process.destroy()
        process.awaitTerminated() // the difference vs close(): we wait until it's really dead
        boundSessionId?.let { registry.unbind(it) }
        pendingTurn?.cancel()
        if (recorder != null && projector != null) recorder.record(projector.agentStopped(agentId))
    }
}
