package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ConnectorChoice
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/** CYP-122: `connectorKind` is additive on the agent spec/read model + the `connector.optin` wire pin. */
class ConnectorKindModelTest {

    private inline fun <reified T> roundTrip(value: T): T =
        CommJson.decodeFromString(CommJson.encodeToString(value))

    @Test
    fun agentConnectorKindIsAdditiveDefaultsStreamJson() {
        // Default = Connector A; a legacy payload without the field still decodes to the default.
        val a = Agent(id = "be", name = "BE", role = Role.WORKER, worktree = "be")
        assertEquals(ConnectorKind.STREAM_JSON, a.connectorKind)
        val legacy = """{"id":"po","name":"PO","role":"PO","worktree":"po"}"""
        assertEquals(ConnectorKind.STREAM_JSON, CommJson.decodeFromString<Agent>(legacy).connectorKind)
    }

    @Test
    fun agentCarriesMcpWhenSet() {
        val a = Agent("be", "BE", Role.WORKER, "be", connectorKind = ConnectorKind.MCP)
        assertEquals(a, roundTrip(a))
        assertEquals(ConnectorKind.MCP, roundTrip(a).connectorKind)
    }

    @Test
    fun newAgentSpecConnectorKindDefaultsStreamJson_andRoundTrips() {
        val legacy = """{"id":"x","name":"X","role":"WORKER"}"""
        assertEquals(ConnectorKind.STREAM_JSON, CommJson.decodeFromString<NewAgentSpec>(legacy).connectorKind)
        val mcp = NewAgentSpec("x", "X", Role.WORKER, connectorKind = ConnectorKind.MCP)
        assertEquals(mcp, roundTrip(mcp))
    }

    @Test
    fun connectorChoiceRoundTrips() {
        val c = ConnectorChoice(ConnectorKind.MCP)
        assertEquals(c, roundTrip(c))
        assertEquals("{\"connectorKind\":\"mcp\"}", CommJson.encodeToString(c))
    }

    @Test
    fun connectorOptinEventTypeWirePinned() {
        assertEquals(EventType.CONNECTOR_OPTIN, EventType.fromWire("connector.optin"))
        assertEquals("connector.optin", EventType.CONNECTOR_OPTIN.wire)
    }
}
