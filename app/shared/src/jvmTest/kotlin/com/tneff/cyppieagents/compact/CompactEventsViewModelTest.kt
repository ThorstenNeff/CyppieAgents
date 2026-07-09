package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.eventlog.StubEventsApi
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-327 Feature B — the compact-events source keeps ONLY the four orchestration types (NOT COMPACT_TRIGGERED),
 * from history + live, de-duplicated by id and seq-ordered. Run-scoping is the panel's job (by correlationId).
 */
class CompactEventsViewModelTest {

    private fun ev(seq: Long, type: EventType, cid: String? = "r1") = Event(
        id = "e$seq", ts = 1_000 + seq, seq = seq, agentId = "po", projectId = "team-1",
        type = type, severity = Severity.INFO, correlationId = cid, sessionId = null,
    )

    /** A live source that never completes and emits only what the test pushes (no reconnect churn). */
    private class PushLive : EventLiveSource {
        val bus = MutableSharedFlow<EventLiveEvent>(extraBufferCapacity = 16)
        override fun events(filter: EventFilter): Flow<EventLiveEvent> = bus
    }

    private fun vm(api: StubEventsApi, live: EventLiveSource) =
        CompactEventsViewModel(api, live, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun history_keepsOnlyTheFourTypes_excludesTriggeredAndNonCompact() {
        val api = StubEventsApi(
            listOf(
                ev(1, EventType.COMPACT_TRIGGERED),        // EXCLUDED (native-compaction observation, not a run event)
                ev(2, EventType.COMPACT_PREPARE_SENT),
                ev(3, EventType.COMPACT_REQUEST_SENT),
                ev(4, EventType.COMPACT_COMPLETED),
                ev(5, EventType.COMPACT_ORCHESTRATION_DONE),
                ev(6, EventType.TURN_START),               // EXCLUDED (non-compact)
            ),
        )
        val vm = vm(api, PushLive())
        val types = vm.events.value.map { it.type }.toSet()
        assertEquals(
            setOf(EventType.COMPACT_PREPARE_SENT, EventType.COMPACT_REQUEST_SENT, EventType.COMPACT_COMPLETED, EventType.COMPACT_ORCHESTRATION_DONE),
            types,
        )
        assertFalse(vm.events.value.any { it.type == EventType.COMPACT_TRIGGERED }, "COMPACT_TRIGGERED must be excluded")
    }

    @Test
    fun live_foldsNewCompactEvent_dedupedBySeqOrder() {
        val live = PushLive()
        val vm = vm(StubEventsApi(listOf(ev(2, EventType.COMPACT_PREPARE_SENT))), live)
        assertEquals(1, vm.events.value.size)
        live.bus.tryEmit(EventLiveEvent.Received(ev(3, EventType.COMPACT_COMPLETED)))
        live.bus.tryEmit(EventLiveEvent.Received(ev(2, EventType.COMPACT_PREPARE_SENT))) // duplicate id e2 → deduped
        val evs = vm.events.value
        assertEquals(2, evs.size, "the live compact event is folded; the duplicate id is deduped")
        assertTrue(evs.map { it.seq } == listOf(2L, 3L), "seq-ordered")
    }
}
