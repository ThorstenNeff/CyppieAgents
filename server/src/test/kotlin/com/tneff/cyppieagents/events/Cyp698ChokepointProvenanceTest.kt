package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * CYP-698 — wire-provenance at the SINGLE write chokepoint. **Acceptance tooth for the fix.**
 *
 * ### The property (not the implementation)
 * A `comm.sent` event must be emitted for EVERY post that passes through [Hub.postAsAgent], regardless of
 * WHICH caller made the post — because the emit lives at the chokepoint ([Hub.onSent], fired inside
 * `postAsAgent`), not in any caller. Before CYP-698 only the MediationRouter path emitted comm.sent; the
 * remote `/ws/hub` WireSend, the human CommRoutes, and the MCP `hub_send` paths all posted SILENTLY — the
 * exact history the dogfood's 6 remote agents would otherwise leave no trace of.
 *
 * ### Why a 5th, un-instrumented caller
 * The tooth drives an [UninstrumentedCaller] that does NOT emit comm.sent itself. If provenance lived in the
 * callers, this path would be silent; the chokepoint must catch it. This is exactly the "future 5th path"
 * the fix promises to cover for free.
 *
 * ### MUTATION
 * Delete `onSent(message)` from [Hub.postAsAgent] (i.e. push the emit back into the callers) → this test goes
 * RED: the un-instrumented caller's post produces no comm.sent. Load-bearing, not vacuous.
 */
class Cyp698ChokepointProvenanceTest {

    private fun agents() = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "BE", Role.WORKER, "backend"))

    /**
     * A caller of the chokepoint with NO comm.sent emit of its own — the stand-in for the remote-wire /
     * human / MCP paths (and any future path) that never emitted before CYP-698. It only calls postAsAgent.
     */
    private class UninstrumentedCaller(private val hub: Hub) {
        fun sendOnBehalf(agentId: String, channelId: String, body: String) =
            hub.postAsAgent(senderId = agentId, channelId = channelId, body = body, meta = MessageMeta(kind = MessageKind.NOTE))
    }

    @Test
    fun commSentEmittedAtChokepoint_forAnUninstrumentedCaller_exactlyOnce_noBody() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        val projector = EventProjector(ContextUsageBander(), projectId = "team")
        val hub = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), InMemoryMessageStore())
        // Production wiring (BootOrchestrator): provenance at the chokepoint, emitted once per post.
        hub.onSent = { msg -> recorder.record(projector.commSent(msg.from, msg.channelId, msg.meta?.kind, msg.projectId)) }

        // A caller that emits NOTHING of its own posts through the chokepoint (backend → its own spoke).
        UninstrumentedCaller(hub).sendOnBehalf("backend", "po-backend", "chokepoint-probe SECRET-NEEDLE")

        withTimeout(5_000) {
            while (sink.query(EventFilter(agentId = "backend"), Page(limit = 100)).events.none { it.type == EventType.COMM_SENT }) {
                delay(10)
            }
        }
        val commSent = sink.query(EventFilter.ALL, Page(limit = 100)).events.filter { it.type == EventType.COMM_SENT }
        assertEquals(1, commSent.size, "exactly ONE comm.sent per post — the emit MOVED to the chokepoint, not duplicated")
        val e = commSent.single()
        assertEquals("backend", e.agentId, "server-stamped sender identity (from the bound Message, never a frame/body field)")
        assertTrue(e.detail.toString().contains("po-backend"), "the channel is stamped into the event")
        assertFalse(e.detail.keys.contains("body"), "metadata only — never a body key")
        assertFalse(e.detail.toString().contains("SECRET-NEEDLE"), "the message body must never leak into the event")

        recorder.stop()
        scope.cancel()
    }
}
