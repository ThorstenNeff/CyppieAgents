package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.claudeCodeServerSession
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

/**
 * ST3 (CYP-37) wiring: the connector tap records projected events into the sink, and the router
 * records `comm.sent` metadata on a successful mediation. Driven via a fake process (no real claude).
 */
class ConnectorTapTest {

    private class FakeAgentProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        val written = CopyOnWriteArrayList<String>()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) { written.add(line) }
        override fun destroy() { lines.close() }
    }

    private fun agents() = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))

    @Test
    fun tapRecordsProjectedEvents_andRouterRecordsCommSent() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        val projector = EventProjector(ContextUsageBander(), projectId = "team")
        val hub = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), InMemoryMessageStore())
        // CYP-698: comm.sent now rides the Hub chokepoint (was MediationRouter's own emit) — wire it as boot does.
        hub.onSent = { msg -> recorder.record(projector.commSent(msg.from, msg.channelId, msg.meta?.kind, msg.projectId)) }
        val registry = SessionRegistry()
        val router = MediationRouter(registry, hub)
        val proc = FakeAgentProcess()
        val session = claudeCodeServerSession("backend", proc, registry, router, SessionTurnQueue(), scope, recorder, projector)
        session.start()

        proc.feed("""{"type":"system","subtype":"init","session_id":"sess-1"}""")
        proc.feed(
            """{"type":"assistant","session_id":"sess-1","message":{"content":[
               {"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"echo SECRET-NEEDLE"}}]}}""",
        )
        proc.feed("""{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","result":"ok"}""")

        suspend fun typesInSink() = sink.query(EventFilter.ALL, Page(limit = 100)).events.map { it.type }.toSet()
        withTimeout(5_000) {
            while (!typesInSink().containsAll(setOf(EventType.TOOL_CALL, EventType.RESULT_FINAL, EventType.COMM_SENT))) {
                delay(10)
            }
        }

        val events = sink.query(EventFilter.ALL, Page(limit = 100)).events
        // tool.call recorded with name, not the command
        val toolCall = events.first { it.type == EventType.TOOL_CALL }
        assertEquals("backend", toolCall.agentId)
        assertFalse(toolCall.detail.toString().contains("SECRET-NEEDLE"), "tap must record metadata-only")
        // comm.sent recorded for the mediated post (from/channel, no body)
        val commSent = events.first { it.type == EventType.COMM_SENT }
        assertTrue(commSent.detail.toString().contains("po-backend"))
        assertFalse(commSent.detail.keys.contains("body"))

        recorder.stop()
        session.close()
        scope.cancel()
    }
}
