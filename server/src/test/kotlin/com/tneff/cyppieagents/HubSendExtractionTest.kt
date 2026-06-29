package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.mediation.HubSendArgs
import com.tneff.cyppieagents.mediation.HubSendCommand
import com.tneff.cyppieagents.mediation.HubTools
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-131 — the mediator extracts agent-initiated `hub_send` tool-calls from the stream-json stream
 * and routes them through the SAME chokepoint as Connector B (`HubMcpTools.send` → `Hub.postAsAgent`):
 * `from` server-stamped, `canWrite` enforced, malformed args fail-closed.
 */
class HubSendExtractionTest {

    private class FakeAgentProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }
    }

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    private fun hub() = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())

    private fun toolUse(input: JsonObject, name: String = HubTools.SEND) = ToolUseBlock("toolu_1", name, input)

    // ---- Collector-level (the CYP-131 branch in ClaudeCodeSession.start) ----

    @Test
    fun collectorExtractsHubSendAndRoutesThroughFunnel() = runBlocking {
        val hub = hub()
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = ClaudeCodeSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()
        delay(50)

        proc.feed(
            """{"type":"assistant","session_id":"sess-1","message":{"content":[
               {"type":"tool_use","id":"toolu_1","name":"hub_send","input":{"channel":"po-backend","text":"do X"}}]}}""",
        )
        withTimeout(3000) { while (hub.channelMessages("po", "po-backend").isEmpty()) delay(10) }

        val posted = hub.channelMessages("po", "po-backend")
        assertEquals(1, posted.size)
        assertEquals("backend", posted.first().from)  // server identity = the session's agentId
        assertEquals("do X", posted.first().body)

        session.close()
        scope.cancel()
    }

    @Test
    fun collectorIgnoresNonHubSendToolCalls() = runBlocking {
        val hub = hub()
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = ClaudeCodeSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()
        delay(50)

        proc.feed("""{"type":"system","subtype":"init","session_id":"sess-1"}""") // bind session→agent for onResult
        proc.feed(
            """{"type":"assistant","session_id":"sess-1","message":{"content":[
               {"type":"tool_use","id":"toolu_2","name":"Bash","input":{"command":"ls"}}]}}""",
        )
        proc.feed("""{"type":"result","subtype":"success","session_id":"sess-1","result":"done"}""")
        // After the result is mediated (worker STATUS→spoke), there must be exactly that ONE post — the Bash
        // tool_use produced none.
        withTimeout(3000) { while (hub.channelMessages("po", "po-backend").isEmpty()) delay(10) }
        delay(50)
        assertEquals(listOf("done"), hub.channelMessages("po", "po-backend").map { it.body })

        session.close()
        scope.cancel()
    }

    // ---- Router-level (onHubSend) ----

    @Test
    fun onHubSendStampsServerIdentityIgnoringInputFrom() {
        val hub = hub()
        val router = MediationRouter(SessionRegistry(), hub)
        val posted = router.onHubSend(
            "backend",
            toolUse(buildJsonObject { put("channel", "po-backend"); put("text", "x"); put("from", "operator") }),
        )
        assertEquals("backend", posted?.from)  // never the spoofed input `from`
    }

    @Test
    fun onHubSendEnforcesCanWriteFailClosed() {
        val hub = hub()
        val router = MediationRouter(SessionRegistry(), hub)
        // backend is NOT a member of po-frontend → canWrite=false → 403, nothing persisted.
        assertFailsWith<ForbiddenException> {
            router.onHubSend("backend", toolUse(buildJsonObject { put("channel", "po-frontend"); put("text", "intrusion") }))
        }
        assertTrue(hub.channelMessages("po", "po-frontend").isEmpty())
    }

    @Test
    fun malformedHubSendPostsNothing() {
        val hub = hub()
        val router = MediationRouter(SessionRegistry(), hub)
        // missing `text` → null, nothing posted (fail-closed, no guess).
        val r = router.onHubSend("backend", toolUse(buildJsonObject { put("channel", "po-backend") }))
        assertNull(r)
        assertTrue(hub.channelMessages("po", "po-backend").isEmpty())
    }

    // ---- Parse (the only new logic) ----

    @Test
    fun parseValidArgsWithOptionalKind() {
        assertEquals(
            HubSendCommand("po-backend", "go", MessageKind.TASK),
            HubSendArgs.parse(buildJsonObject { put("channel", "po-backend"); put("text", "go"); put("kind", "TASK") }),
        )
        // Unknown kind is ignored (null), the command still parses.
        assertEquals(
            HubSendCommand("po-backend", "go", null),
            HubSendArgs.parse(buildJsonObject { put("channel", "po-backend"); put("text", "go"); put("kind", "BOGUS") }),
        )
    }

    @Test
    fun parseRejectsMissingBlankOrNonStringArgs() {
        assertNull(HubSendArgs.parse(buildJsonObject { put("channel", "po-backend") }))   // missing text
        assertNull(HubSendArgs.parse(buildJsonObject { put("text", "x") }))               // missing channel
        assertNull(HubSendArgs.parse(buildJsonObject { put("channel", "  "); put("text", "x") })) // blank channel
        assertNull(HubSendArgs.parse(buildJsonObject { put("channel", 5); put("text", "x") }))    // non-string channel
    }
}
