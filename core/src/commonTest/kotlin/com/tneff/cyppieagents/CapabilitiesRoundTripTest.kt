package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `:core` contract test for the Doc-10 capability DTOs (CYP-120). They compile into both `:server`
 * (connectors declare them) and `:app:shared` (UI surfaces degradation), so the wire shape must
 * round-trip and the enum wire-names must be pinned — same stance as [SerializationRoundTripTest].
 */
class CapabilitiesRoundTripTest {

    private inline fun <reified T> roundTrip(value: T): T {
        val json = CommJson.encodeToString(value)
        return CommJson.decodeFromString<T>(json)
    }

    @Test
    fun capabilitiesRoundTripAcrossAllStatuses() {
        // Cover every status + kind so a dropped @SerialName / field reorder reddens here.
        val c = Capabilities(
            structuredUsage = CapabilityStatus.AVAILABLE,
            toolGranularity = CapabilityStatus.LIMITED,
            reliableResult = CapabilityStatus.UNAVAILABLE,
            rateLimitSignal = CapabilityStatus.LIMITED,
            coordination = CapabilityStatus.AVAILABLE,
            kind = ConnectorKind.MCP,
        )
        assertEquals(c, roundTrip(c))
    }

    @Test
    fun statusWireNamesArePinned() {
        // The UI keys off these wire strings; a rename is a breaking contract change, caught here.
        assertEquals("\"available\"", CommJson.encodeToString(CapabilityStatus.AVAILABLE))
        assertEquals("\"limited\"", CommJson.encodeToString(CapabilityStatus.LIMITED))
        assertEquals("\"unavailable\"", CommJson.encodeToString(CapabilityStatus.UNAVAILABLE))
    }

    @Test
    fun connectorKindWireNamesArePinned() {
        assertEquals("\"stream_json\"", CommJson.encodeToString(ConnectorKind.STREAM_JSON))
        assertEquals("\"mcp\"", CommJson.encodeToString(ConnectorKind.MCP))
    }

    @Test
    fun capabilitiesFieldKeysAreStable() {
        val json = CommJson.encodeToString(
            Capabilities(
                structuredUsage = CapabilityStatus.AVAILABLE,
                toolGranularity = CapabilityStatus.AVAILABLE,
                reliableResult = CapabilityStatus.AVAILABLE,
                rateLimitSignal = CapabilityStatus.AVAILABLE,
                coordination = CapabilityStatus.AVAILABLE,
                kind = ConnectorKind.STREAM_JSON,
            ),
        )
        for (key in listOf(
            "structuredUsage", "toolGranularity", "reliableResult",
            "rateLimitSignal", "coordination", "kind",
        )) {
            assertTrue(json.contains("\"$key\""), "missing field key '$key' in $json")
        }
    }

    @Test
    fun agentCapabilitiesIsAdditiveAndDefaultsNull() {
        // The CYP-122 population field must be additive: config-time Agent(...) stays null-valued, and a
        // legacy payload without `capabilities` still decodes (older clients/configs unaffected).
        val a = Agent(id = "backend", name = "Backend", role = Role.WORKER, worktree = "backend")
        assertNull(a.capabilities)
        assertEquals(a, roundTrip(a))

        val legacy = """{"id":"po","name":"PO","role":"PO","worktree":"po","runState":"RUNNING"}"""
        assertNull(CommJson.decodeFromString<Agent>(legacy).capabilities)
    }

    @Test
    fun agentCarriesCapabilitiesWhenPopulated() {
        // The steady-state read shape Dev scaffolds against: capabilities travel on the Agent DTO.
        val a = Agent(
            id = "backend", name = "Backend", role = Role.WORKER, worktree = "backend",
            capabilities = Capabilities(
                structuredUsage = CapabilityStatus.AVAILABLE,
                toolGranularity = CapabilityStatus.AVAILABLE,
                reliableResult = CapabilityStatus.AVAILABLE,
                rateLimitSignal = CapabilityStatus.AVAILABLE,
                coordination = CapabilityStatus.AVAILABLE,
                kind = ConnectorKind.STREAM_JSON,
            ),
        )
        assertEquals(a, roundTrip(a))
    }
}
