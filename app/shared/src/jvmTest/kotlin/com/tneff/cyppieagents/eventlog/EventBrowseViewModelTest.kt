package com.tneff.cyppieagents.eventlog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Browse VM behavior on JVM ([runBlocking]) with an injected `Dispatchers.Unconfined` scope, so the
 * `StubEventsApi` queries resolve inline and assertions are deterministic without a test scheduler.
 */
class EventBrowseViewModelTest {

    @Test
    fun loadsFirstPageOrderedBySeq() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventBrowseViewModel(StubEventsApi(), scope = scope)
        val seqs = vm.state.value.events.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        assertFalse(vm.state.value.loading)
        assertEquals(7, vm.state.value.events.size)
        scope.cancel()
    }

    @Test
    fun pagesViaAfterSeqCursorWithoutDuplicates() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventBrowseViewModel(StubEventsApi(), pageSize = 3, scope = scope)
        assertEquals(3, vm.state.value.events.size)
        assertTrue(vm.state.value.hasMore)
        vm.loadMore() // afterSeq=3 → 4,5,6
        vm.loadMore() // afterSeq=6 → 7
        assertEquals(7, vm.state.value.events.size)
        assertFalse(vm.state.value.hasMore)
        assertEquals(7, vm.state.value.events.distinctBy { it.id }.size) // no overlap across pages
        scope.cancel()
    }

    @Test
    fun showRunDrillsIntoCorrelationInSeqOrder() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventBrowseViewModel(StubEventsApi(), scope = scope)
        val anEvent = vm.state.value.events.first { it.correlationId == "run-1" }
        vm.showRun(anEvent)
        assertEquals(DrilldownAxis.CORRELATION, vm.state.value.drilldown)
        assertTrue(vm.state.value.events.all { it.correlationId == "run-1" })
        assertEquals(5, vm.state.value.events.size) // only the run-1 chain, not the run-2 noise
        val seqs = vm.state.value.events.map { it.seq }
        assertEquals(seqs.sorted(), seqs)
        scope.cancel()
    }

    @Test
    fun clearDrilldownRestoresBaseFilter() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventBrowseViewModel(StubEventsApi(), scope = scope)
        val total = vm.state.value.events.size
        vm.showRun(vm.state.value.events.first { it.correlationId == "run-1" })
        assertEquals(5, vm.state.value.events.size)
        vm.clearDrilldown()
        assertNull(vm.state.value.drilldown)
        assertEquals(total, vm.state.value.events.size)
        scope.cancel()
    }

    @Test
    fun showRunIsNoOpWithoutCorrelationId() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val noCid = Event(
            id = "x", ts = 1, seq = 1, agentId = "a", teamId = "t",
            type = EventType.TURN_START, severity = Severity.INFO, correlationId = null,
        )
        val vm = EventBrowseViewModel(StubEventsApi(listOf(noCid)), scope = scope)
        vm.showRun(noCid)
        assertNull(vm.state.value.drilldown)
        scope.cancel()
    }

    @Test
    fun selectSetsDetail() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val vm = EventBrowseViewModel(StubEventsApi(), scope = scope)
        val e = vm.state.value.events.first()
        vm.select(e)
        assertEquals(e, vm.state.value.selected)
        scope.cancel()
    }
}
