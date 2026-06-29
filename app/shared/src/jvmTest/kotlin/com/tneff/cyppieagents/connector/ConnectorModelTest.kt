package com.tneff.cyppieagents.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-123 model honesty: the compact-badge aggregate must pick the **worst** dimension (never optimistic),
 * and the B acknowledgment invariant must hold. Pure, mutation-minded.
 */
class ConnectorModelTest {

    private fun caps(
        su: CapabilityStatus = CapabilityStatus.AVAILABLE,
        tg: CapabilityStatus = CapabilityStatus.AVAILABLE,
        rr: CapabilityStatus = CapabilityStatus.AVAILABLE,
        rl: CapabilityStatus = CapabilityStatus.AVAILABLE,
        co: CapabilityStatus = CapabilityStatus.AVAILABLE,
    ) = Capabilities(su, tg, rr, rl, co)

    @Test
    fun overall_picksWorst_neverOptimistic() {
        assertEquals(CapabilityStatus.AVAILABLE, caps().overall(), "all available → available")
        assertEquals(
            CapabilityStatus.LIMITED,
            caps(tg = CapabilityStatus.LIMITED).overall(),
            "one limited (rest available) → limited, not available",
        )
        assertEquals(
            CapabilityStatus.UNAVAILABLE,
            caps(su = CapabilityStatus.UNAVAILABLE, tg = CapabilityStatus.LIMITED).overall(),
            "any unavailable dominates limited → unavailable",
        )
    }

    @Test
    fun isDegraded_trueWhenAnyDimensionBelowAvailable() {
        assertFalse(caps().isDegraded, "all available → not degraded")
        assertTrue(caps(co = CapabilityStatus.LIMITED).isDegraded, "any non-available → degraded (badge marks it)")
    }

    @Test
    fun dimensions_areTheFiveInDeclaredOrder() {
        val dims = caps().dimensions.map { it.dimension }
        assertEquals(
            listOf(
                CapabilityDimension.STRUCTURED_USAGE,
                CapabilityDimension.TOOL_GRANULARITY,
                CapabilityDimension.RELIABLE_RESULT,
                CapabilityDimension.RATE_LIMIT_SIGNAL,
                CapabilityDimension.COORDINATION,
            ),
            dims,
        )
    }

    @Test
    fun acknowledgment_onlyRequiredForB() {
        assertTrue(ConnectorSelection(ConnectorKind.STREAM_JSON, riskAcknowledged = false).isAcknowledgmentSatisfied, "A needs no ack")
        assertFalse(ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = false).isAcknowledgmentSatisfied, "B without ack → not satisfied")
        assertTrue(ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = true).isAcknowledgmentSatisfied, "B with ack → satisfied")
    }

    @Test
    fun defaultCapabilities_AisFull_BisDegraded() {
        assertFalse(defaultCapabilitiesFor(ConnectorKind.STREAM_JSON).isDegraded, "A (stream-json) = full fidelity")
        assertTrue(defaultCapabilitiesFor(ConnectorKind.MCP).isDegraded, "B (MCP) = declared lower fidelity")
        assertEquals(
            CapabilityStatus.AVAILABLE,
            defaultCapabilitiesFor(ConnectorKind.MCP).coordination,
            "B coordination is GOOD via MCP tools (Doc 10 §4) — degraded is not uniform",
        )
    }
}
