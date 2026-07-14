package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * CYP-579 — the **"unrenderable event" replay placeholder**. When a persisted `event_json` row fails to decode
 * (a schema-drift after a [StreamJsonEvent] variant/field change, or a corrupt/partially-written row), the
 * store [query] emits THIS at the row's OWN `seq` instead of silently dropping it — so the transcript gap is
 * **visible** (a marked system row) rather than an invisible hole (the CYP-575/Root-C anti-pattern: make the
 * absence positive). It is a [UserEvent] with a non-null `injectedSource`, which the client `StreamJsonMapper`
 * already renders as an `IncomingSystem` row (the CYP-326 injected-message path) — so NO client change is
 * needed. Same `seq` ⇒ the `?since=` cursor/dedup stays intact; a unique `uuid` ⇒ `foldEvent`'s id-dedup keeps
 * it to one row across replays. Single-sourced here so the Sqlite + Pg stores can never drift.
 *
 * The text is a server-side diagnostic (English, not localized — the mapper deliberately holds no user literal);
 * a localized render is a follow-up (a small mapper change keyed on `injectedSource == "cyppie/replay"`).
 */
internal fun unrenderableEventPlaceholder(seq: Long): StreamJsonEvent =
    UserEvent(
        message = AgentMessage(
            role = "system",
            content = listOf(TextBlock("⚠ Unrenderable stored event (seq $seq) — persisted but could not be decoded")),
        ),
        uuid = "$UNRENDERABLE_UUID_PREFIX$seq",
        injectedSource = UNRENDERABLE_INJECTED_SOURCE,
    )

/** CYP-579 — the placeholder's `injectedSource` tag (the render-path key) + uuid prefix, single-sourced for tests. */
internal const val UNRENDERABLE_INJECTED_SOURCE = "cyppie/replay"
internal const val UNRENDERABLE_UUID_PREFIX = "unrenderable-"

/**
 * CYP-198 — durable, replayable persistence of the per-agent stream-json transcript, the **single source**
 * the `/ws/agent` window reads from (so a reconnect replays history instead of a blank screen; today the
 * connector's `_events` is `replay=0`). LOSSLESS within a retention window (the agent window IS the
 * turn-for-turn narrative; coalescing would destroy it). Masked events only. Covers BOTH the local
 * connector path AND the remote `WireEvent` path (the side-project runs on a remote agent).
 *
 * Mirrors the `EventSink` (CYP-35) discipline: WAL + gapless seq + a live [Flow] fan-out.
 */
interface AgentEventStore {
    /** Append one MASKED event; assigns + returns the gapless [StoredAgentEvent.seq]. */
    suspend fun append(agentId: String, projectId: String, tsMs: Long, event: StreamJsonEvent): StoredAgentEvent

    /**
     * The window's single source: replay every durable event for [agentId] with `seq > sinceSeq` (oldest→
     * newest), THEN stream live appends — one ordered, gapless, **de-duplicated** stream. Attaching to live
     * happens BEFORE the replay query and live is drained with a `seq > cursor` guard, so there is no
     * gap and no dup across the history→live boundary (the race the design calls out).
     */
    fun subscribe(agentId: String, sinceSeq: Long? = null): Flow<StoredAgentEvent>

    /** REST backfill: durable events for [agentId] with `seq > sinceSeq`, oldest→newest, capped at [limit]. */
    suspend fun query(agentId: String, sinceSeq: Long? = null, limit: Int = 1_000): List<StoredAgentEvent>

    /** Cascade cleanup (mirrors EventSink.deleteByProject) — remove all events of a deleted project. */
    suspend fun deleteByProject(projectId: String): Int
}

/**
 * Process-local [AgentEventStore] for the token-only path + focused tests. NOT durable (a restart loses
 * history — [SqliteAgentEventStore] is the durable production store), but same seq/replay-then-live/retention
 * semantics so the teeth prove the contract here too.
 */
class InMemoryAgentEventStore(private val retainPerAgent: Int = DEFAULT_RETAIN_PER_AGENT) : AgentEventStore {
    private val mutex = Mutex()
    private val rows = ArrayList<StoredAgentEvent>()
    private var nextSeq = 1L
    private val live = MutableSharedFlow<StoredAgentEvent>(extraBufferCapacity = 256)

    override suspend fun append(agentId: String, projectId: String, tsMs: Long, event: StreamJsonEvent): StoredAgentEvent {
        val stored = mutex.withLock {
            val s = StoredAgentEvent(nextSeq++, agentId, projectId, tsMs, event)
            rows.add(s)
            // retention: keep the last [retainPerAgent] per agent (lossless within the window).
            if (retainPerAgent > 0) {
                val idxForAgent = rows.withIndex().filter { it.value.agentId == agentId }
                if (idxForAgent.size > retainPerAgent) {
                    idxForAgent.take(idxForAgent.size - retainPerAgent).map { it.index }.sortedDescending().forEach { rows.removeAt(it) }
                }
            }
            s
        }
        live.emit(stored)
        return stored
    }

    override fun subscribe(agentId: String, sinceSeq: Long?): Flow<StoredAgentEvent> = flow {
        var cursor = sinceSeq ?: 0L
        // CYP-198 race fix (see SqliteAgentEventStore): onSubscription registers this collector on `live`
        // BEFORE the replay query runs, so a concurrent append can't slip through the gap; dedup by seq.
        live
            .onSubscription { query(agentId, cursor, Int.MAX_VALUE).forEach { emit(it); cursor = maxOf(cursor, it.seq) } }
            .collect { rec -> if (rec.agentId == agentId && rec.seq > cursor) { emit(rec); cursor = rec.seq } }
    }

    override suspend fun query(agentId: String, sinceSeq: Long?, limit: Int): List<StoredAgentEvent> = mutex.withLock {
        rows.asSequence()
            .filter { it.agentId == agentId && it.seq > (sinceSeq ?: 0L) }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override suspend fun deleteByProject(projectId: String): Int = mutex.withLock {
        val before = rows.size
        rows.removeAll { it.projectId == projectId }
        before - rows.size
    }

    companion object { const val DEFAULT_RETAIN_PER_AGENT = 2_000 }
}

/**
 * CYP-198 — the ordered, non-blocking feeder from the [com.tneff.cyppieagents.connector.SessionObserver] tap
 * to the [AgentEventStore]. The observer's `onEvent` runs on the session read-loop and MUST NOT block (no
 * Observer-Effect), so [record] only `trySend`s to an UNLIMITED queue (the ENQUEUE never fails/blocks); a
 * single consumer drains it to the store IN ORDER, so each agent's transcript keeps its emit order + a gapless seq.
 *
 * **CYP-579 (honesty fix):** the durable [AgentEventStore.append] CAN fail (SQLITE_BUSY / disk-full / PG down /
 * constraint), and this consumer must not die — so the append is wrapped in `runCatching`. Previously the
 * failure was **swallowed with no log** while this KDoc claimed "lossless" (a contract lie): a live write blip
 * silently lost a transcript event → a `/ws/agent` replay gap with zero signal (the CYP-575 class). The failure
 * is now **logged (WARN)** so the drop is diagnosable. The event IS still dropped (no retry) — closing that gap
 * with retry/dead-letter to make the path genuinely lossless is the ratified **follow-on** (not this ticket).
 */
class AgentEventRecorder(
    private val store: AgentEventStore,
    scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val log = LoggerFactory.getLogger("agentevents.recorder")
    private data class Item(val agentId: String, val projectId: String, val event: StreamJsonEvent)
    private val queue = Channel<Item>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in queue) {
                runCatching { store.append(item.agentId, item.projectId, nowMs(), item.event) }
                    // CYP-579: never swallow silently — a dropped durable event is now visible in the logs (the
                    // CYP-575 diagnosability fix). Retry/dead-letter (true losslessness) = follow-on ticket.
                    .onFailure { log.warn("CYP-579: durable agent-event append FAILED — event DROPPED (no retry): agent={} project={}", item.agentId, item.projectId, it) }
            }
        }
    }

    /** Non-blocking: enqueue one MASKED event for durable, in-order append (called from the observer tap). */
    fun record(agentId: String, projectId: String, event: StreamJsonEvent) {
        queue.trySend(Item(agentId, projectId, event))
    }
}
