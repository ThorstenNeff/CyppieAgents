package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-123 model contract — the honesty invariants over the `:core` connector DTOs:
 *  - [isDegraded] is true iff ANY dimension is below AVAILABLE (mutation: make it constant-false → RED);
 *  - [rows] are the five fields in declared order (mutation: reorder/drop a field → RED);
 *  - [ConnectorSelection.isAcknowledgmentSatisfied] is fail-closed for B (mutation: drop the B guard → RED);
 *  - [defaultCapabilitiesFor] declares A full-fidelity and B honestly degraded (with coordination kept).
 */
class ConnectorModelTest {

    private fun caps(
        structuredUsage: CapabilityStatus = CapabilityStatus.AVAILABLE,
        toolGranularity: CapabilityStatus = CapabilityStatus.AVAILABLE,
        reliableResult: CapabilityStatus = CapabilityStatus.AVAILABLE,
        rateLimitSignal: CapabilityStatus = CapabilityStatus.AVAILABLE,
        coordination: CapabilityStatus = CapabilityStatus.AVAILABLE,
        kind: ConnectorKind = ConnectorKind.STREAM_JSON,
    ) = Capabilities(structuredUsage, toolGranularity, reliableResult, rateLimitSignal, coordination, kind)

    @Test
    fun isDegraded_falseWhenAllAvailable_trueWhenAnyReduced() {
        assertFalse(caps().isDegraded, "all AVAILABLE → full fidelity, not degraded")
        // Each single reduction must flip the aggregate (mutation: const-false → these go RED).
        assertTrue(caps(structuredUsage = CapabilityStatus.UNAVAILABLE).isDegraded)
        assertTrue(caps(toolGranularity = CapabilityStatus.LIMITED).isDegraded)
        assertTrue(caps(reliableResult = CapabilityStatus.UNAVAILABLE).isDegraded)
        assertTrue(caps(rateLimitSignal = CapabilityStatus.LIMITED).isDegraded)
        assertTrue(caps(coordination = CapabilityStatus.UNAVAILABLE).isDegraded)
    }

    @Test
    fun rows_areTheFiveFieldsInDeclaredOrder() {
        // Distinct statuses per field so a reordering/duplication mutation is provable.
        val c = caps(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.LIMITED,
            reliableResult = CapabilityStatus.UNAVAILABLE,
            rateLimitSignal = CapabilityStatus.LIMITED,
            coordination = CapabilityStatus.AVAILABLE,
        )
        val rows = c.rows
        assertEquals(5, rows.size)
        assertEquals(CapabilityDimension.STRUCTURED_USAGE, rows[0].dimension)
        assertEquals(CapabilityStatus.AVAILABLE, rows[0].status)
        assertEquals(CapabilityDimension.TOOL_GRANULARITY, rows[1].dimension)
        assertEquals(CapabilityStatus.LIMITED, rows[1].status)
        assertEquals(CapabilityDimension.RELIABLE_RESULT, rows[2].dimension)
        assertEquals(CapabilityStatus.UNAVAILABLE, rows[2].status)
        assertEquals(CapabilityDimension.RATE_LIMIT_SIGNAL, rows[3].dimension)
        assertEquals(CapabilityStatus.LIMITED, rows[3].status)
        assertEquals(CapabilityDimension.COORDINATION, rows[4].dimension)
        assertEquals(CapabilityStatus.AVAILABLE, rows[4].status)
    }

    @Test
    fun acknowledgment_aAlwaysSatisfied_bNeedsAck() {
        // A never needs the ack (even unacknowledged).
        assertTrue(ConnectorSelection(ConnectorKind.STREAM_JSON, riskAcknowledged = false).isAcknowledgmentSatisfied)
        assertTrue(ConnectorSelection(ConnectorKind.STREAM_JSON, riskAcknowledged = true).isAcknowledgmentSatisfied)
        // B is fail-closed: unsatisfied until acknowledged (mutation: drop the B guard → first assert RED).
        assertFalse(ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = false).isAcknowledgmentSatisfied)
        assertTrue(ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = true).isAcknowledgmentSatisfied)
    }

    @Test
    fun defaultCapabilities_aFull_bDegraded_withCoordinationKept() {
        val a = defaultCapabilitiesFor(ConnectorKind.STREAM_JSON)
        assertFalse(a.isDegraded, "A (stream-json) is the first-class full-fidelity default")
        assertEquals(ConnectorKind.STREAM_JSON, a.kind)

        val b = defaultCapabilitiesFor(ConnectorKind.MCP)
        assertTrue(b.isDegraded, "B (MCP) is honestly degraded")
        assertEquals(ConnectorKind.MCP, b.kind)
        assertEquals(CapabilityStatus.AVAILABLE, b.coordination, "B keeps coordination AVAILABLE (MCP tools)")
    }
}
