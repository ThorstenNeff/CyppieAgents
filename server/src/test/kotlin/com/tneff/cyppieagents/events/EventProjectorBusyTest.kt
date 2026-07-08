package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-324 — the projector's [EventProjector.onBusy] hook, the REAL session-state seam behind `/ws/busy-state`
 * (RecordingSessionObserver drives turnStart / result / processExit / agentStopped into these methods).
 * `turn.start` → busy, `result` / process-exit / stop / restart → idle. UNGATED by capabilities (busy/idle is
 * process-level, not usage-fidelity) — so a Connector-B agent still shows `*`.
 *
 * Mutation that reds these: swap any `onBusy(agentId, true/false)` value, or drop a call → the matching
 * assertion reds.
 */
class EventProjectorBusyTest {

    private fun caps(usage: CapabilityStatus) = Capabilities(
        structuredUsage = usage, toolGranularity = CapabilityStatus.AVAILABLE, reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE, coordination = CapabilityStatus.AVAILABLE, kind = ConnectorKind.MCP,
    )

    private val result: StreamJsonEvent = CommJson.decodeFromString(
        """{"type":"result","subtype":"success","is_error":false,"session_id":"s","uuid":"u","usage":{"input_tokens":10}}""",
    )

    private fun projector(resolve: ((String) -> Capabilities?)?, sink: (String, Boolean) -> Unit) =
        EventProjector(ContextUsageBander(), projectId = "t", capabilities = resolve, onBusy = sink)

    @Test
    fun turnStart_marksBusy_thenResult_marksIdle() {
        val busy = ArrayList<Pair<String, Boolean>>()
        val p = projector({ caps(CapabilityStatus.AVAILABLE) }) { id, b -> busy.add(id to b) }
        p.turnStart("backend", "s", "c")
        p.project("backend", "s", "c", result)
        assertEquals(listOf("backend" to true, "backend" to false), busy, "turn.start → busy, result → idle")
    }

    @Test
    fun processExit_marksIdle_soADeadAgentNeverHangsBusy() {
        val busy = ArrayList<Pair<String, Boolean>>()
        val p = projector({ caps(CapabilityStatus.AVAILABLE) }) { id, b -> busy.add(id to b) }
        p.turnStart("backend", "s", "c")
        p.processExit("backend", "s", 1)
        assertEquals(listOf("backend" to true, "backend" to false), busy, "died mid-turn → idle")
    }

    @Test
    fun agentStopped_andRestarted_markIdle() {
        val busy = ArrayList<Pair<String, Boolean>>()
        val p = projector({ caps(CapabilityStatus.AVAILABLE) }) { id, b -> busy.add(id to b) }
        p.agentStopped("backend")
        p.agentRestarted("backend")
        assertEquals(listOf("backend" to false, "backend" to false), busy, "stop/restart → idle")
    }

    @Test
    fun busy_isUngated_connectorB_stillSignals() {
        // structuredUsage UNAVAILABLE (Connector-B) — token feed would be null, but busy/idle is process-level.
        val busy = ArrayList<Pair<String, Boolean>>()
        val p = projector({ caps(CapabilityStatus.UNAVAILABLE) }) { id, b -> busy.add(id to b) }
        p.turnStart("backend", "s", "c")
        p.project("backend", "s", "c", result)
        assertEquals(listOf("backend" to true, "backend" to false), busy, "busy fires regardless of usage fidelity")
    }
}
