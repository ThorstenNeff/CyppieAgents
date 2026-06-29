package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ConnectorRouter
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.McpConnector
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.support.FakeConnector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** CYP-122: per-agent connector selection — the router resolves each agent's kind to the serving connector. */
class ConnectorRouterTest {

    private val streamJson = FakeConnector.uniform(CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON)
    private val mcp = FakeConnector.uniform(CapabilityStatus.LIMITED, ConnectorKind.MCP)

    private fun router(kinds: Map<String, ConnectorKind>) =
        ConnectorRouter(streamJson, mcp, kindOf = { kinds[it] ?: ConnectorKind.STREAM_JSON })

    @Test
    fun selectsConnectorByAgentKind() {
        val r = router(mapOf("a" to ConnectorKind.STREAM_JSON, "b" to ConnectorKind.MCP))
        assertSame(streamJson, r.forAgent("a"))
        assertSame(mcp, r.forAgent("b"))
        // open delegates to the serving connector (FakeConnector records the agentId it opened).
        r.open("a"); r.open("b")
        assertEquals(listOf("a"), streamJson.opened)
        assertEquals(listOf("b"), mcp.opened)
    }

    @Test
    fun capabilitiesForReturnsTheServingConnectorsCaps() {
        val r = router(mapOf("a" to ConnectorKind.STREAM_JSON, "b" to ConnectorKind.MCP))
        assertEquals(ConnectorKind.STREAM_JSON, r.capabilitiesFor("a").kind)
        assertEquals(ConnectorKind.MCP, r.capabilitiesFor("b").kind)
        assertEquals(CapabilityStatus.AVAILABLE, r.capabilitiesFor("a").structuredUsage)
        assertEquals(CapabilityStatus.LIMITED, r.capabilitiesFor("b").structuredUsage)
    }

    @Test
    fun unknownAgentDefaultsToStreamJson() {
        assertSame(streamJson, router(emptyMap()).forAgent("ghost"))
    }

    @Test
    fun capabilitiesForKindIsSingleSourcedFromTheConnectorImpls() {
        assertEquals(ClaudeCodeConnector.STREAM_JSON_CAPABILITIES, ConnectorRouter.capabilitiesForKind(ConnectorKind.STREAM_JSON))
        assertEquals(McpConnector.MCP_CAPABILITIES, ConnectorRouter.capabilitiesForKind(ConnectorKind.MCP))
    }
}
