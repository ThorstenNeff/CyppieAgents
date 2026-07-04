package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
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
            .onSubscription { query(agentId, cursor, Int.MAX_VALUE).forEach { emit(it) } }
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
 * Observer-Effect), so [record] only `trySend`s to an UNLIMITED queue (never fails, never blocks, never
 * drops → lossless); a single consumer drains it to the store IN ORDER, so each agent's transcript keeps
 * its emit order + a gapless seq.
 */
class AgentEventRecorder(
    private val store: AgentEventStore,
    scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Item(val agentId: String, val projectId: String, val event: StreamJsonEvent)
    private val queue = Channel<Item>(Channel.UNLIMITED)

    init { scope.launch { for (item in queue) runCatching { store.append(item.agentId, item.projectId, nowMs(), item.event) } } }

    /** Non-blocking: enqueue one MASKED event for durable, in-order append (called from the observer tap). */
    fun record(agentId: String, projectId: String, event: StreamJsonEvent) {
        queue.trySend(Item(agentId, projectId, event))
    }
}
