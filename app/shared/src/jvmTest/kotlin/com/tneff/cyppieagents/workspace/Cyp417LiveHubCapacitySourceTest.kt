package com.tneff.cyppieagents.workspace

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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-417 (S-G) — the **Stub→live swap glue** ([LiveHubCapacitySource]). Pins the honest decode of Backend's
 * real seam: the mount-time snapshot leads, `capacity.changed` decodes the content-free `{current, estimatedMax?}`
 * counters (estimatedMax absent ⇒ `null` ⇒ the "N aktiv" no-max readout), `spawn.rejected` becomes one bare reject
 * signal, and a 1008 **AccessRevoked is terminal** (the fast [Backoff] would otherwise let `.reconnecting()` re-open
 * `/ws/events` forever — the scripted flows END with AccessRevoked, so a lost guard would HANG this test, not pass).
 */
class Cyp417LiveHubCapacitySourceTest {

    private val zeroBackoff = Backoff(initialMs = 0, maxMs = 0)

    private fun event(type: EventType, current: Int?, estimatedMax: Int?) = Event(
        id = "e", ts = 0, seq = 0, agentId = "po", projectId = "default",
        type = type, severity = if (type == EventType.SPAWN_REJECTED) Severity.WARN else Severity.INFO,
        detail = buildJsonObject {
            current?.let { put("current", it) }
            estimatedMax?.let { put("estimatedMax", it) }
        },
    )

    /** Fake `/ws/events`: returns the scripted stream for the subscribed [EventFilter.type]. */
    private class FakeEvents(private val perType: Map<EventType?, List<EventLiveEvent>>) : EventLiveSource {
        override fun events(filter: EventFilter): Flow<EventLiveEvent> =
            (perType[filter.type] ?: emptyList()).asFlow()
    }

    private fun source(
        capacityStream: List<EventLiveEvent> = emptyList(),
        rejectStream: List<EventLiveEvent> = emptyList(),
        snapshot: HubCapacity? = null,
    ) = LiveHubCapacitySource(
        events = FakeEvents(
            mapOf(
                EventType.CAPACITY_CHANGED to capacityStream,
                EventType.SPAWN_REJECTED to rejectStream,
            ),
        ),
        snapshot = { snapshot },
        backoff = zeroBackoff,
    )

    @Test
    fun capacity_emitsSnapshotThenDecodesCapacityChanged() = runTest {
        val src = source(
            capacityStream = listOf(
                EventLiveEvent.Connected, // ignored — not a capacity value
                EventLiveEvent.Received(event(EventType.CAPACITY_CHANGED, current = 3, estimatedMax = 5)),
                EventLiveEvent.AccessRevoked, // terminal → the flow completes (no reconnect hammer)
            ),
            snapshot = HubCapacity(2, 5),
        )
        assertEquals(listOf(HubCapacity(2, 5), HubCapacity(3, 5)), src.capacity().toList())
    }

    @Test
    fun capacity_noMax_decodesToNullEstimatedMax() = runTest {
        // estimatedMax absent on the wire ⇒ null ⇒ drives the "N aktiv" no-max readout (A2), never an invented 0.
        val src = source(
            capacityStream = listOf(
                EventLiveEvent.Received(event(EventType.CAPACITY_CHANGED, current = 4, estimatedMax = null)),
                EventLiveEvent.AccessRevoked,
            ),
        )
        assertEquals(listOf(null, HubCapacity(4, null)), src.capacity().toList())
    }

    @Test
    fun capacity_failedSnapshot_isAbsent() = runTest {
        // snapshot null (non-2xx / unauth GET /api/capacity) ⇒ the readout is absent (H1), never "0/0".
        val src = source(capacityStream = listOf(EventLiveEvent.AccessRevoked), snapshot = null)
        assertEquals(listOf(null), src.capacity().toList())
    }

    @Test
    fun rejections_emitOnePerSpawnRejected() = runTest {
        val src = source(
            rejectStream = listOf(
                EventLiveEvent.Connected,
                EventLiveEvent.Received(event(EventType.SPAWN_REJECTED, current = 4, estimatedMax = 4)),
                EventLiveEvent.Received(event(EventType.SPAWN_REJECTED, current = 4, estimatedMax = 4)),
                EventLiveEvent.AccessRevoked,
            ),
        )
        assertEquals(2, src.rejections().toList().size) // one bare signal per real reject, content-free
    }
}
