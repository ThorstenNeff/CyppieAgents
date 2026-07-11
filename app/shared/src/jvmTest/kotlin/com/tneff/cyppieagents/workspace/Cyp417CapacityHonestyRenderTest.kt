package com.tneff.cyppieagents.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.eventlog.EventFilter
import com.tneff.cyppieagents.eventlog.EventLiveEvent
import com.tneff.cyppieagents.eventlog.EventLiveSource
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-417 (S-G) — **independent QA cross-check**, deliberately NON-duplicative. Dev's `Cyp417CapacityRenderTest`
 * renders the pill from **hand-made** `HubCapacity(...)`; Dev's `Cyp417LiveHubCapacitySourceTest` checks the
 * decoded FLOW values via `toList`; Dev's VM test already pins full-readout-alone-shows-no-banner. **What no test
 * does is connect the REAL source to the RENDERED pill** — i.e. prove the number an operator SEES is the SERVER's
 * `CAPACITY_CHANGED` value, decoded by the real [LiveHubCapacitySource], not a hand-made or client-derived count.
 *
 * That is the source-of-truth honesty (axis-19: an echo/hand-made value can't prove provenance) driven end-to-end
 * with a **divergent** fixture: a single `CAPACITY_CHANGED` on the wire → the real decode → the actual pill text.
 * The impl wires no client roster near the capacity path, so the rendered number's only source is this event; a
 * decode mutation (read the wrong field) makes the pill show the wrong number → RED. Default test locale =
 * `values/strings.xml` (DE): `hubcap_readout` = `%1$s/%2$s Agenten`, `hubcap_readout_nomax` = `%1$s aktiv`.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp417CapacityHonestyRenderTest {

    private val zeroBackoff = Backoff(initialMs = 0, maxMs = 0)

    private fun capEvent(current: Int?, estimatedMax: Int?) = Event(
        id = "e", ts = 0, seq = 0, agentId = "po", projectId = "default",
        type = EventType.CAPACITY_CHANGED, severity = Severity.INFO,
        detail = buildJsonObject {
            current?.let { put("current", it) }
            estimatedMax?.let { put("estimatedMax", it) }
        },
    )

    private class FakeEvents(private val perType: Map<EventType?, List<EventLiveEvent>>) : EventLiveSource {
        override fun events(filter: EventFilter): Flow<EventLiveEvent> =
            (perType[filter.type] ?: emptyList()).asFlow()
    }

    /** One `CAPACITY_CHANGED` on the wire, decoded by the REAL source; AccessRevoked terminates the finite stream. */
    private suspend fun decodeFromRealSource(current: Int?, estimatedMax: Int?): HubCapacity? =
        LiveHubCapacitySource(
            events = FakeEvents(
                mapOf(
                    EventType.CAPACITY_CHANGED to listOf(
                        EventLiveEvent.Received(capEvent(current, estimatedMax)),
                        EventLiveEvent.AccessRevoked,
                    ),
                ),
            ),
            snapshot = { null },
            backoff = zeroBackoff,
        ).capacity().toList().last()

    /** T1 — N/M: the pill an operator SEES is the REAL source's decoded server-event value (divergent 7/9).
     *  Decode fully completes (runBlocking) BEFORE the Compose test starts — no builder nesting. Asserts the
     *  locale-robust NUMBER "7/9" (the source-of-truth value; the copy wording is part-1's verbatim job). */
    @Test
    fun pillShowsServerEventCount_endToEnd_withMax() {
        val cap = runBlocking { decodeFromRealSource(current = 7, estimatedMax = 9) }
        assertEquals(HubCapacity(7, 9), cap, "REAL source decoded the server event verbatim (7/9)")
        runComposeUiTest {
            setContent { MaterialTheme { CapacityReadout(cap) } }
            onNodeWithTag(WorkspaceTags.CAPACITY, useUnmergedTree = true).assertExists()
            onNodeWithText("7/9", substring = true, useUnmergedTree = true).assertExists()
            onNodeWithTag(WorkspaceTags.CAPACITY_FULL, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    /** T2 — no-max path end-to-end: absent `estimatedMax` on the wire → the real decode → the pill shows the
     *  distinctive count 42 present-but-NOT-full (the "N aktiv" / no-max render). */
    @Test
    fun pillShowsServerEventCount_endToEnd_noMax() {
        val cap = runBlocking { decodeFromRealSource(current = 42, estimatedMax = null) }
        assertEquals(HubCapacity(42, null), cap, "REAL source decoded current with null max (42, no ceiling)")
        runComposeUiTest {
            setContent { MaterialTheme { CapacityReadout(cap) } }
            onNodeWithTag(WorkspaceTags.CAPACITY, useUnmergedTree = true).assertExists()
            onNodeWithText("42", substring = true, useUnmergedTree = true).assertExists()
            onNodeWithTag(WorkspaceTags.CAPACITY_FULL, useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
