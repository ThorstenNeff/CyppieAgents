package com.tneff.cyppieagents.agentevents

import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

/** CYP-599 — the durable catch-up page size (bounded backfill; replaces the old `Int.MAX_VALUE` re-query). */
internal const val AGENT_EVENT_CATCHUP_CHUNK = 512

/**
 * CYP-588 — the shared **replay-then-live with GAP-DETECT** [subscribe][AgentEventStore.subscribe] body for EVERY
 * store (single-sourced so Sqlite/Pg/InMemory can't drift). The live fan-out [live] may **DROP_OLDEST** under a slow
 * consumer (prod Sqlite/Pg deliberately use DROP_OLDEST so a slow `/ws/agent` client never blocks `append`) — which
 * silently discards a mid-stream live event (`tryEmit` returns `true` on a DROP_OLDEST drop, so the drop is invisible
 * to the producer). The naive `if (seq > cursor)` cursor would then jump PAST the dropped seq → a **permanent**
 * transcript hole (a client's `?since=<lastSeq>` reconnect never re-fetches a seq below its cursor). The dropped rows
 * ARE durable, so a **`seq > cursor + 1` jump triggers a durable catch-up** that backfills the missing rows in order —
 * no hole. [onGap] is the diagnosability hook (a WARN naming the gap).
 *
 * **CYP-599 STORM-GUARD.** Under a runaway producer (one agent flooding events while `/ws/agent` is backpressured)
 * the naive per-gap re-query self-amplified: every dropped event became its OWN **unbounded** (`Int.MAX_VALUE`) durable
 * re-query + a WARN, run INSIDE the live collector so it blocked live-buffer draining → the next gap grew → a bigger
 * re-query → more drops (positive feedback; 1310 WARNs in the 2026-07-15 dogfood), and on Sqlite the single-connection
 * mutex was monopolized → sibling streams starved. The guard, single-sourced here:
 *  - **bounded backfill** — the catch-up reads in fixed [catchupChunk] pages (NOT `Int.MAX_VALUE`), so each [query] +
 *    each Sqlite mutex hold is bounded and RELEASED between pages (siblings get the lock → no starvation);
 *  - **gap-coalesce** — one detected gap drains the durable store to the CURRENT latest in a single paged catch-up (NOT
 *    one re-query per dropped event); the live rows dropped meanwhile are irrelevant (the durable store is the source of
 *    truth) and any later live row `<= cursor` is skipped by the dedup;
 *  - **rate-capped [onGap]** — fired ONCE per lag-episode (entry into "behind"), re-armed only after the stream is
 *    caught up (a contiguous live event), so a sustained storm logs once, not once per dropped event.
 * No-permanent-loss + ordering + no-dup are preserved (every durable `seq > cursor` is delivered ascending; the
 * `seq <= cursor` dedup carries the history→live and post-catch-up boundaries). InMemory's `live` is SUSPEND (its
 * `append` `emit`s, never drops) → the gap branch never fires there, so this is behaviour-preserving for it.
 */
internal fun agentEventReplayThenLive(
    agentId: String,
    sinceSeq: Long?,
    live: SharedFlow<StoredAgentEvent>,
    query: suspend (agentId: String, sinceSeq: Long?, limit: Int) -> List<StoredAgentEvent>,
    catchupChunk: Int = AGENT_EVENT_CATCHUP_CHUNK,
    onGap: (fromSeq: Long, toSeq: Long) -> Unit = { _, _ -> },
): Flow<StoredAgentEvent> = flow {
    var cursor = sinceSeq ?: 0L
    var behind = false // CYP-599 rate-cap: one onGap per lag-episode, re-armed when the stream catches up
    // onSubscription registers on `live` BEFORE the initial catch-up (CYP-198 race fix) so a concurrent append can't
    // slip the boundary; dedup by `seq > cursor`.
    live
        .onSubscription { cursor = drainDurableAgentEvents(agentId, cursor, catchupChunk, query) }
        .collect { rec ->
            if (rec.agentId != agentId || rec.seq <= cursor) return@collect // dedup (also skips already-backfilled rows)
            if (rec.seq == cursor + 1) {
                emit(rec); cursor = rec.seq
                behind = false // a contiguous live event ⇒ caught up ⇒ re-arm the episode WARN
            } else {
                // CYP-588/599 GAP: live rows (cursor+1 .. rec.seq-1) were DROP_OLDEST-dropped. Coalesced, bounded catch-up
                // from the durable store (append writes the DB BEFORE tryEmit, so every gap seq is durable → no hole).
                if (!behind) { onGap(cursor + 1, rec.seq - 1); behind = true } // CYP-599 rate-cap: once per episode
                cursor = drainDurableAgentEvents(agentId, cursor, catchupChunk, query) // drain to the CURRENT latest (coalesce)
                if (rec.seq == cursor + 1) { emit(rec); cursor = rec.seq } // rec appended just past the catch-up snapshot
            }
        }
}

/**
 * CYP-599 — the bounded, paged durable catch-up: emit every durable row with `seq > [cursor]` in ascending order,
 * reading [chunk]-sized pages so the store lock (Sqlite's single-connection mutex) is held per page and RELEASED
 * between pages (no monopoly ⇒ sibling streams progress). Returns the advanced cursor. Terminates on a short/empty page
 * (nothing left) or a non-advancing page (defensive against a non-monotone query — never an infinite loop).
 */
private suspend fun FlowCollector<StoredAgentEvent>.drainDurableAgentEvents(
    agentId: String,
    cursor: Long,
    chunk: Int,
    query: suspend (agentId: String, sinceSeq: Long?, limit: Int) -> List<StoredAgentEvent>,
): Long {
    var c = cursor
    while (true) {
        val page = query(agentId, c, chunk)
        var advanced = false
        for (row in page) if (row.seq > c) { emit(row); c = row.seq; advanced = true }
        if (page.size < chunk || !advanced) break
    }
    return c
}

/**
 * CYP-198 — durable, replayable persistence of the per-agent stream-json transcript, the **single source**
 * the `/ws/agent` window reads from (so a reconnect replays history instead of a blank screen; today the
 * connector's `_events` is `replay=0`). LOSSLESS within a retention window (the agent window IS the
 * turn-for-turn narrative; coalescing would destroy it). Masked events only. Covers BOTH the local
 * connector path AND the remote `WireEvent` path (the side-project runs on a remote agent).
 *
 * Mirrors the `EventSink` (CYP-35) discipline: WAL + gapless seq + a live [Flow] fan-out.
 */
@com.tneff.cyppieagents.tier.StoreKey("agent_events")
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

    // CYP-588: the shared replay-then-live-with-gap-detect. InMemory's `live` is SUSPEND (its append `emit`s, never
    // drops) → the gap branch never fires here; using the shared body keeps it drift-free with the durable stores.
    override fun subscribe(agentId: String, sinceSeq: Long?): Flow<StoredAgentEvent> =
        agentEventReplayThenLive(agentId, sinceSeq, live, ::query)

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
