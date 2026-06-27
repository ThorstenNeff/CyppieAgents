package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Live-Tail VM behavior on JVM ([runBlocking]) with an injected `Dispatchers.Unconfined` scope: a hot
 * source delivers each emission inline, so assertions are deterministic WITHOUT a test scheduler — this
 * keeps the VM tests free of `kotlinx-coroutines-test` (no shared version-catalog change). The VM logic
 * itself is `commonMain`.
 */
class EventTailViewModelTest {

    private class FakeSource(val flow: MutableSharedFlow<EventLiveEvent>) : EventLiveSource {
        override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow
    }

    private fun ev(seq: Long) = Event(
        id = "e$seq", ts = seq, seq = seq, agentId = "a", teamId = "t",
        type = EventType.TURN_START, severity = Severity.INFO,
    )

    private fun source() = MutableSharedFlow<EventLiveEvent>(extraBufferCapacity = 64)

    @Test
    fun streamsOrderedAndDeduped() = runBlocking {
        val flow = source()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventTailViewModel(FakeSource(flow), scope = scope)
        flow.emit(EventLiveEvent.Connected)
        flow.emit(EventLiveEvent.Received(ev(2)))
        flow.emit(EventLiveEvent.Received(ev(1)))
        flow.emit(EventLiveEvent.Received(ev(2))) // duplicate id → deduped
        assertEquals(ConnectionStatus.LIVE, vm.state.value.connection)
        assertEquals(listOf(1L, 2L), vm.state.value.events.map { it.seq })
        scope.cancel()
    }

    @Test
    fun pauseBuffersAndResumeFlushesWithoutLoss() = runBlocking {
        val flow = source()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventTailViewModel(FakeSource(flow), scope = scope)
        flow.emit(EventLiveEvent.Received(ev(1)))
        vm.pause()
        flow.emit(EventLiveEvent.Received(ev(2)))
        flow.emit(EventLiveEvent.Received(ev(3)))
        // visible list frozen at [1]; two events buffered while paused
        assertEquals(listOf(1L), vm.state.value.events.map { it.seq })
        assertEquals(2, vm.state.value.pendingCount)
        assertTrue(vm.state.value.paused)

        vm.resume()
        assertFalse(vm.state.value.paused)
        assertEquals(0, vm.state.value.pendingCount)
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.events.map { it.seq }) // no loss, no dup
        scope.cancel()
    }

    @Test
    fun ringCapTrimsOldestAndCountsVisibly() = runBlocking {
        val flow = source()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventTailViewModel(FakeSource(flow), ringCapacity = 3, scope = scope)
        for (s in 1L..5L) flow.emit(EventLiveEvent.Received(ev(s)))
        assertEquals(listOf(3L, 4L, 5L), vm.state.value.events.map { it.seq })
        assertEquals(2, vm.state.value.trimmedCount) // surfaced, not silent
        scope.cancel()
    }

    @Test
    fun disconnectSetsStatus() = runBlocking {
        val flow = source()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventTailViewModel(FakeSource(flow), scope = scope)
        flow.emit(EventLiveEvent.Disconnected)
        assertEquals(ConnectionStatus.DISCONNECTED, vm.state.value.connection)
        scope.cancel()
    }
}
