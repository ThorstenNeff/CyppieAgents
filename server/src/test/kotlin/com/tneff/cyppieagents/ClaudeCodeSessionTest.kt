package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.SecretMasker
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.claudeCodeServerSession
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        val session = claudeCodeServerSession("backend", proc, registry, router, SessionTurnQueue(), scope)
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

        // CYP-45: await the ACTUAL asserted conditions, not a proxy. The hub post (mediation) runs on
        // an independent path AFTER `_events.emit`, so waiting only for the ResultEvent on `received`
        // raced the post → flaky `expected:<1> but was:<0>`. Wait for both the collected assistant
        // event AND the mediated post before asserting.
        withTimeout(3000) {
            while (received.filterIsInstance<AssistantEvent>().isEmpty() ||
                hub.channelMessages("po", "po-backend").isEmpty()
            ) {
                delay(10)
            }
        }

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

    /** Models a process that takes time to die: [awaitTerminated] parks until [terminate] is fired. */
    private class TerminatingProcess : AgentProcess {
        val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
        val terminate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override val stdoutLines: Flow<String> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() { destroyed.set(true) }
        override suspend fun awaitTerminated() { terminate.await() }
    }

    @Test
    fun closeAndAwait_waitsForProcessTermination_noZombie() = runBlocking {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val proc = TerminatingProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = claudeCodeServerSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()

        val closing = scope.launch { session.closeAndAwait() }
        // It must request destruction but NOT return until the process has actually terminated.
        withTimeout(2000) { while (!proc.destroyed.get()) delay(5) }
        delay(50)
        assertTrue(closing.isActive, "closeAndAwait must wait for process termination (no zombie writing to the bus)")

        proc.terminate.complete(Unit)
        withTimeout(2000) { closing.join() }
        assertFalse(closing.isActive, "returns once the process is confirmed gone")

        scope.cancel()
    }

    @Test
    fun sendTurnIsSingleFlightUntilResult() = runBlocking {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val session = claudeCodeServerSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
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
    fun turnQueueStaysSerialAcrossSessionIdTransition() = runTest {
        val hub = Hub(HubState.hubAndSpoke(agents()), InMemoryMessageStore())
        val registry = SessionRegistry()
        val proc = FakeAgentProcess()
        // CYP-164 de-flake: run the session's reader + both turns on an UnconfinedTestDispatcher bound to the
        // test scheduler. Launched coroutines run EAGERLY to their next real suspension point, and
        // [advanceUntilIdle] flushes any scheduler-queued continuations (e.g. a fed line resuming the reader),
        // so each assertion reads a SETTLED state. This replaces the fixed `delay(200)` + shared
        // `Dispatchers.Default` that raced under high parallel load — the timing bet is gone, determinism stays.
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = claudeCodeServerSession("backend", proc, registry, MediationRouter(registry, hub), SessionTurnQueue(), scope)
        session.start()

        // Turn#1 before the CLI reports its session_id.
        val t1 = scope.launch { session.sendTurn(UserTurn("one")) }
        advanceUntilIdle()
        assertEquals(1, proc.written.size)
        assertTrue(t1.isActive)

        // system/init arrives mid-session — under the old code this switched the turn key.
        proc.feed("""{"type":"system","subtype":"init","session_id":"sess-1"}""")
        advanceUntilIdle()
        assertEquals("backend", registry.agentFor("sess-1"))

        // Turn#2 attempts while Turn#1 still holds — it must queue, NOT inject (stable-key serial). It runs
        // eagerly up to the HELD mutex and parks there; if the stable-key serialization broke (key transition
        // at system/init), t2 would take a different mutex and inject here → size==2 → this assertion reddens.
        val t2 = scope.launch { session.sendTurn(UserTurn("two")) }
        advanceUntilIdle()
        assertEquals(1, proc.written.size) // Turn#2 not injected: it waits for Result#1
        assertTrue(t2.isActive)

        // Result#1 releases Turn#1 → Turn#2 proceeds.
        proc.feed("""{"type":"result","subtype":"success","session_id":"sess-1"}""")
        advanceUntilIdle()
        assertEquals(2, proc.written.size)
        assertFalse(t1.isActive) // Turn#1 completed once its result released the lock

        // Result#2 releases Turn#2.
        proc.feed("""{"type":"result","subtype":"success","session_id":"sess-1"}""")
        advanceUntilIdle()
        assertFalse(t2.isActive)

        session.close()
        scope.cancel()
    }
}
