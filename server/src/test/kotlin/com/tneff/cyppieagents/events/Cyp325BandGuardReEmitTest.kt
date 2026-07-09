package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-325 (defect 1, band-side guard) — a LOAD-BEARING tooth for the band 0-occupancy guard
 * (`if (snapshot.contextTokens > 0L)` in [EventProjector]).
 *
 * QA finding: dev's `Cyp325TokenContextSizeTest.degenerateUsage_producesNoBandEvent…` feeds a SINGLE 0-occupancy
 * result with no prior band → it emits nothing WITH OR WITHOUT the guard (a first 0 never crosses UP), so it is
 * **vacuous for the guard it names** (verified — removing the guard leaves that tooth green). The guard's real
 * job is preventing the marker-RESET a degenerate 0 causes AFTER a real band, which would let the next climb-back
 * re-emit a SPURIOUS duplicate band event. This tooth exercises exactly that:
 *   good(250k → band-20) → degenerate(0) → good(250k):
 *   WITH the guard the marker stays 20 → the 3rd result is not a new up-crossing → exactly ONE band event.
 *   WITHOUT the guard the degenerate resets the marker to 0 → the 3rd re-crosses into band-20 → TWO events.
 * Reds on the band-guard mutation (`if (snapshot.contextTokens > 0L)` → `if (true)`).
 */
class Cyp325BandGuardReEmitTest {

    private fun caps() = Capabilities(
        structuredUsage = CapabilityStatus.AVAILABLE,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.MCP,
    )

    private fun result(usageJson: String): StreamJsonEvent = CommJson.decodeFromString(
        """{"type":"result","subtype":"success","is_error":false,"session_id":"s","uuid":"u","usage":$usageJson}""",
    )

    @Test
    fun degenerateAfterBand_doesNotResetMarker_soClimbBackDoesNotReEmit() {
        val proj = EventProjector(ContextUsageBander(), projectId = "t", capabilities = { caps() })
        val good = """{"input_tokens":250000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}""" // 25% → band-20
        val degenerate = """{"output_tokens":5}""" // 0 occupancy — must NOT reset the band marker
        val band = buildList {
            addAll(proj.project("backend", "s", "c", result(good)))       // → band-20 (1st up-crossing)
            addAll(proj.project("backend", "s", "c", result(degenerate))) // guarded: no band, marker stays 20
            addAll(proj.project("backend", "s", "c", result(good)))       // still band-20 → no new crossing
        }.filter { it.type == EventType.CONTEXT_USAGE }
        assertEquals(1, band.size, "a degenerate 0 must not reset the band marker; the climb-back must not re-emit band-20")
    }
}
