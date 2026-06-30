package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.claudeCodeServerSession
import com.tneff.cyppieagents.mediation.HubSendArgs
import com.tneff.cyppieagents.mediation.HubSendCommand
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.Role
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-146 reconcile + the reused `hub_send` arg contract.
 *
 * The CYP-131 stdout-`hub_send` ROUTING is retired: a Connector-A agent now emits via the in-process Hub
 * MCP server (the single router). The stdout collector must therefore NOT route any `hub_send` tool_use —
 * else, since the MCP server already posts, it would **double-post**. [HubSendArgs] (the arg parse) stays,
 * reused by the MCP handler.
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
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    /**
     * F2 (non-vacuous reconcile guard): feed an assistant `tool_use` named **`mcp__hub__hub_send`** — the
     * REAL prefixed name the in-process MCP tool surfaces in the stream — and assert the stdout collector
     * does NOT post it to the hub. The ONLY post is the worker's `onResult` STATUS (positive control).
     *
     * **Mutation (the teeth):** re-enable a collector branch that matches this `mcp__hub__hub_send` tool_use
     * and routes it → the "INJECTED" body is also posted → 2 messages → this test reddens. (A test using the
     * BARE `hub_send` name would be vacuous: the MCP path never emits that name.)
     */
    @Test
    fun collectorDoesNotRouteMcpHubSend() = runBlocking {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = claudeCodeServerSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()
        delay(50)

        proc.feed("""{"type":"system","subtype":"init","session_id":"sess-1"}""") // bind session→agent
        proc.feed(
            """{"type":"assistant","session_id":"sess-1","message":{"content":[
               {"type":"tool_use","id":"toolu_1","name":"mcp__hub__hub_send","input":{"channel":"po-backend","text":"INJECTED"}}]}}""",
        )
        proc.feed("""{"type":"result","subtype":"success","session_id":"sess-1","result":"status done"}""")

        // Wait for the result mediation (the positive control), then assert exactly ONE post — the STATUS,
        // NOT a second from the tool_use (the collector no longer routes hub_send).
        withTimeout(3000) { while (hub.channelMessages("po", "po-backend").isEmpty()) delay(10) }
        delay(50)
        val posted = hub.channelMessages("po", "po-backend")
        assertEquals(1, posted.size, "exactly one post — the collector must not route the MCP tool_use")
        assertEquals("status done", posted.first().body, "the single post is the onResult STATUS, not INJECTED")

        session.close()
        scope.cancel()
    }

    // ---- HubSendArgs (the shared arg contract, reused by the MCP `hub_send` handler) ----

    @Test
    fun parseValidArgsWithOptionalKind() {
        assertEquals(
            HubSendCommand("po-backend", "go", MessageKind.TASK),
            HubSendArgs.parse(buildJsonObject { put("channel", "po-backend"); put("text", "go"); put("kind", "TASK") }),
        )
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
