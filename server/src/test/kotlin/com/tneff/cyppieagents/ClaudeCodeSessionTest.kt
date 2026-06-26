package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.SecretMasker
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** End-to-end of the live session via a fake process: masking, session binding, mediation, turn-queue. */
class ClaudeCodeSessionTest {

    private class FakeAgentProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        val written = CopyOnWriteArrayList<String>()
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) { written.add(line) }
        override fun destroy() { lines.close() }
    }

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    @Test
    fun masksEvents_bindsSession_andMediatesResult() = runBlocking {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val router = MediationRouter(registry, hub)
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = ClaudeCodeSession("backend", proc, registry, router, SessionTurnQueue(), scope)
        val received = CopyOnWriteArrayList<StreamJsonEvent>()

        session.start()
        val sub = scope.launch { session.events.collect { received.add(it) } }
        delay(100) // ensure the WS-side subscriber is active before feeding (no-replay SharedFlow)

        proc.feed("""{"type":"system","subtype":"init","session_id":"sess-1"}""")
        proc.feed(
            """{"type":"assistant","session_id":"sess-1","message":{"content":[
               {"type":"tool_use","id":"toolu_1","name":"Bash",
                "input":{"command":"echo ANTHROPIC_API_KEY=sk-ant-leakedsecretvalue1234567890"}}]}}""",
        )
        proc.feed("""{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","result":"done; sk-ant-anotherleakvalue0987654321"}""")

        withTimeout(3000) { while (received.none { it is ResultEvent }) delay(10) }

        // Gate #1 source of truth: session_id → agent binding.
        assertEquals("backend", registry.agentFor("sess-1"))

        // Gate #3: tool_use.input masked on the egress stream.
        val toolUse = received.filterIsInstance<AssistantEvent>().first().message.content.first() as ToolUseBlock
        assertFalse(toolUse.input.toString().contains("leakedsecretvalue"))
        assertTrue(toolUse.input.toString().contains(SecretMasker.REDACTED))

        // Gate #6/#1: successful result mediated to the agent's own spoke, masked.
        val posted = hub.channelMessages("po", "po-backend")
        assertEquals(1, posted.size)
        assertFalse(posted.first().body.contains("anotherleakvalue"))

        sub.cancel()
        session.close()
        scope.cancel()
    }

    @Test
    fun sendTurnIsSingleFlightUntilResult() = runBlocking {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = ClaudeCodeSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()

        val turn = scope.launch { session.sendTurn(UserTurn("do it")) }

        // stdin is written as the verified UserTurn NDJSON shape…
        withTimeout(2000) { while (proc.written.isEmpty()) delay(5) }
        assertEquals(1, proc.written.size)
        assertTrue(proc.written.first().contains("\"type\":\"user\""))
        assertTrue(proc.written.first().contains("do it"))

        // …and the turn is held (single-flight) until its result arrives (Gate #5).
        assertTrue(turn.isActive)
        proc.feed("""{"type":"result","subtype":"success","session_id":"backend"}""")
        withTimeout(2000) { turn.join() }
        assertFalse(turn.isActive)

        session.close()
        scope.cancel()
    }

    @Test
    fun turnQueueStaysSerialAcrossSessionIdTransition() = runBlocking {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = ClaudeCodeSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()

        // Turn#1 before the CLI reports its session_id.
        val t1 = scope.launch { session.sendTurn(UserTurn("one")) }
        withTimeout(2000) { while (proc.written.isEmpty()) delay(5) }
        assertEquals(1, proc.written.size)
        assertTrue(t1.isActive)

        // system/init arrives mid-session — under the old code this switched the turn key.
        proc.feed("""{"type":"system","subtype":"init","session_id":"sess-1"}""")
        withTimeout(2000) { while (registry.agentFor("sess-1") == null) delay(5) }

        // Turn#2 attempts while Turn#1 still holds — it must queue, NOT inject (stable-key serial).
        val t2 = scope.launch { session.sendTurn(UserTurn("two")) }
        delay(200)
        assertEquals(1, proc.written.size) // Turn#2 not injected: it waits for Result#1
        assertTrue(t2.isActive)

        // Result#1 releases Turn#1 → Turn#2 proceeds.
        proc.feed("""{"type":"result","subtype":"success","session_id":"sess-1"}""")
        withTimeout(2000) { t1.join() }
        withTimeout(2000) { while (proc.written.size < 2) delay(5) }
        assertEquals(2, proc.written.size)

        proc.feed("""{"type":"result","subtype":"success","session_id":"sess-1"}""")
        withTimeout(2000) { t2.join() }

        session.close()
        scope.cancel()
    }
}
