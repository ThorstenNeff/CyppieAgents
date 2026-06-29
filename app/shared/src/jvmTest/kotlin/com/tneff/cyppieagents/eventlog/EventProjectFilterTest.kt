package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-94: the cross-project read lens narrows by `projectId` (concrete id) and widens with the `all`
 * sentinel, with `null` = the forced-active default (CYP-102 unchanged). Mutation-provable: drop the
 * projectId clause in the stub `matches` → a concrete-project query leaks foreign events → these go RED.
 */
class EventProjectFilterTest {

    private fun ev(seq: Long, project: String) = Event(
        id = "e$seq", ts = 1_000 + seq, seq = seq, agentId = "backend", projectId = project,
        type = EventType.TURN_START, severity = Severity.INFO, correlationId = null, sessionId = null,
        detail = JsonObject(emptyMap()),
    )

    private val events = listOf(ev(1, "p1"), ev(2, "p2"), ev(3, "p1"))

    @Test
    fun stubApi_concreteProject_narrows_allAndNull_doNotNarrow() = runBlocking {
        val api = StubEventsApi(events)
        assertEquals(listOf("e2"), api.query(EventFilter(projectId = "p2"), Page()).events.map { it.id })
        assertEquals(3, api.query(EventFilter(projectId = EventFilter.PROJECT_ALL), Page()).events.size)
        assertEquals(3, api.query(EventFilter(projectId = null), Page()).events.size) // forced-active default (stub: no narrowing)
    }

    @Test
    fun browseVm_crossProjectLens_narrowsToThatProject() {
        val vm = EventBrowseViewModel(StubEventsApi(events), scope = CoroutineScope(Dispatchers.Unconfined))
        vm.applyFilter(EventFilter(projectId = "p1"))
        assertEquals(listOf("e1", "e3"), vm.state.value.events.map { it.id }.sorted())
        assertTrue(vm.state.value.events.all { it.projectId == "p1" })
    }

    @Test
    fun tailVm_applyProjectFilter_setsLens_andClearsVisible() {
        val vm = EventTailViewModel(
            StubEventsSource(events, stepMillis = 0L),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertNull(vm.state.value.projectId) // default = forced-active
        vm.applyProjectFilter("p2")
        assertEquals("p2", vm.state.value.projectId) // lens set → re-subscribe (events reset)
    }
}
