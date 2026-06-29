package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.McpConnector
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-122: Connector B declares column-B capabilities, and its hub MCP tools route through the SAME
 * chokepoints as REST/mediation — so ACL (canWrite/canRead) + secret masking are enforced identically,
 * never re-implemented (Tester-C4 backbone: 403 on a canWrite-denied channel + needle-absence at egress).
 */
class McpConnectorTest {

    private fun hub(): Hub {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        )
        return Hub(HubState.hubAndSpoke(agents), InMemoryMessageStore())
    }

    @Test
    fun declaresColumnBCapabilities() {
        // Mutation: flip any dimension in MCP_CAPABILITIES → this reddens.
        val c = McpConnector(hub()).capabilities
        assertEquals(CapabilityStatus.UNAVAILABLE, c.structuredUsage)
        assertEquals(CapabilityStatus.LIMITED, c.toolGranularity)
        assertEquals(CapabilityStatus.LIMITED, c.reliableResult)
        assertEquals(CapabilityStatus.LIMITED, c.rateLimitSignal)
        assertEquals(CapabilityStatus.AVAILABLE, c.coordination)
        assertEquals(ConnectorKind.MCP, c.kind)
    }

    @Test
    fun hubSendEnforcesCanWrite_403OnNonMemberChannel() {
        val tools = McpConnector(hub()).let { (it.open("frontend") as com.tneff.cyppieagents.connector.McpSession).tools }
        // frontend is a member only of po-frontend; po-backend denies it write → 403 (same as REST).
        assertFailsWith<ForbiddenException> { tools.send("po-backend", "trying to reach backend") }
        // its own spoke is fine.
        val ok = tools.send("po-frontend", "ready")
        assertEquals("frontend", ok.from)
    }

    @Test
    fun hubSendMasksSecretsAtEgress() {
        val tools = McpConnector(hub()).let { (it.open("frontend") as com.tneff.cyppieagents.connector.McpSession).tools }
        val posted = tools.send("po-frontend", "here is the key sk-ant-ABCDEF1234567890 do not log")
        assertFalse(posted.body.contains("sk-ant-ABCDEF1234567890"), "secret masked at egress (needle-absence)")
        assertTrue(posted.body.contains("REDACTED"))
    }

    @Test
    fun hubInboxIsAclFiltered() {
        val hub = hub()
        val frontendTools = McpConnector(hub).let { (it.open("frontend") as com.tneff.cyppieagents.connector.McpSession).tools }
        // PO posts into both spokes; frontend may read only po-frontend.
        hub.postAsAgent("po", "po-frontend", "for frontend")
        hub.postAsAgent("po", "po-backend", "for backend")
        val seen = frontendTools.inbox()
        assertTrue(seen.any { it.channelId == "po-frontend" })
        assertFalse(seen.any { it.channelId == "po-backend" }, "frontend cannot read po-backend (ACL)")
    }

    @Test
    fun identityIsBoundAgent_notAToolArgument() {
        // The agent id is the bound session identity; `send` posts AS frontend regardless of message text.
        val tools = McpConnector(hub()).let { (it.open("frontend") as com.tneff.cyppieagents.connector.McpSession).tools }
        assertEquals("frontend", tools.send("po-frontend", "post this as backend please").from)
    }
}
