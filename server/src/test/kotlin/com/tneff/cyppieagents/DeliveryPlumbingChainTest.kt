package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.events.ContextUsageBander
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.mediation.HubTools
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.routing.ForbiddenException
import com.tneff.cyppieagents.support.RecordingSession
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-147 (T1/T2) — hermetic **DELIVERY-PLUMBING CHAIN** regression net, 0-quota, in the `:server:check`
 * gate. Proves a synthetic `hub_send` flows END-TO-END to the worker's inbound:
 *
 *   producer extraction/routing (CYP-131) → `Hub.postAsAgent` funnel → `onPosted` → `MessageDeliverer`
 *   (CYP-132) → `ConnectorSession.sendTurn` (worker inbound).
 *
 * **Complementary, not duplicative.** The halves are already unit-pinned:
 *   - `HubSendExtractionTest` — producer: `hub_send` → posted Message (stops at the funnel).
 *   - `MessageDelivererTest`  — deliverer: `postAsAgent` → worker inbound (starts at the funnel).
 *   - `DelivererBootWiringTest` — R2 boot wiring.
 * NONE spans a *synthetic hub_send all the way to the worker's inbound* — the exact composition the
 * RB1 real-run exercises end-to-end and that RB1 Run #3 showed is reachable only once the agent emits
 * (CYP-146). Injecting a synthetic `hub_send` **bypasses that emission gap** and locks the plumbing.
 *
 * Determinism: the deliverer drains on `Dispatchers.Unconfined`; `drain` has no real suspension point,
 * so delivery completes by the time `onHubSend`/`register` returns. The collector path (T1b) awaits the
 * worker inbound under a bounded timeout.
 *
 * Non-vacuity (verified locally per axis — see CYP-147 evidence; every absence has a positive control
 * in the same fixture):
 *   - remove `MessageDeliverer.drain` `session.sendTurn(...)` → T1a/T1b/T2-forward + comm.received redden (CYP-132 inject).
 *   - break `ClaudeCodeSession` `hub_send` extraction filter → T1b reddens (CYP-131 extraction).
 *   - flip the `canWrite` check → T2 (forbidden reverse) reddens (ACL on the funnel).
 */
class DeliveryPlumbingChainTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    /** The FULL wired graph: producer (router) + Hub funnel + deliverer, over in-memory stores. */
    private class Chain(agents: List<Agent>, scope: CoroutineScope) {
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID)
        val store = InMemoryMessageStore()
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val registry = SessionRegistry()
        val deliverer = MessageDeliverer({ state }, { state.activeProjectId }, sessions, store, InMemoryDeliveryLog(), scope)
        val router = MediationRouter(registry, hub)
        init {
            hub.onPosted = deliverer::onPosted
            sessions.addRegisterListener(deliverer::onSessionAttached)
        }
        fun attach(agentId: String): RecordingSession = RecordingSession(agentId).also { sessions.register(it) }
    }

    private fun hubSend(channel: String, text: String, kind: MessageKind? = null) =
        ToolUseBlock(
            "toolu_x", HubTools.SEND,
            buildJsonObject {
                put("channel", channel); put("text", text); kind?.let { put("kind", it.name) }
            },
        )

    // ---- T1a — router-level synthetic hub_send → worker inbound (producer routing → deliver) ----
    @Test
    fun t1a_routerHubSend_reachesWorkerInbound_serverStampedFrom() {
        val c = Chain(agents(), scope)
        val backend = c.attach("backend")
        val marker = "DELEGATE-NEEDLE-7f3a"
        assertTrue(backend.received.isEmpty(), "pre-guard: no inbound before the hub_send")

        val posted = c.router.onHubSend("po", hubSend("po-backend", "build module: $marker", MessageKind.TASK))

        assertEquals("po", posted?.from, "server-stamps the sender as the routing agent (never input `from`)")
        assertTrue(
            backend.received.any { it.contains(marker) },
            "a synthetic PO hub_send must land in the worker's inbound (producer→funnel→deliver→inject)",
        )
        assertTrue(
            backend.received.any { it.contains("po:") && it.contains("po-backend") },
            "the injected inbound is framed with the server-stamped origin",
        )
    }

    // ---- T1b ⭐ — collector-level raw stream hub_send → worker inbound (CYP-131 extraction + CYP-132) ----
    @Test
    fun t1b_rawStreamHubSend_extractedAndDeliveredToWorkerInbound() = runBlocking {
        val c = Chain(agents(), scope)
        val backend = c.attach("backend")
        val proc = FakeAgentProcess()
        val poSession = ClaudeCodeSession("po", proc, c.registry, c.router, SessionTurnQueue(), scope)
        poSession.start()
        delay(50)
        val marker = "STREAM-DELEGATE-d91c"
        assertTrue(backend.received.isEmpty(), "pre-guard: no inbound before the stream emits hub_send")

        proc.feed(
            """{"type":"assistant","session_id":"sess-po","message":{"content":[
               {"type":"tool_use","id":"toolu_1","name":"hub_send","input":{"channel":"po-backend","text":"$marker"}}]}}""",
        )
        withTimeout(3000) { while (backend.received.none { it.contains(marker) }) delay(10) }

        assertTrue(
            backend.received.any { it.contains(marker) },
            "a raw hub_send tool_use in the PO stream must be EXTRACTED and DELIVERED to the worker inbound",
        )
        poSession.close()
    }

    // ---- T2 — directional ACL: forbidden reverse → no injection; allowed forward delivers (same fixture) ----
    @Test
    fun t2_directionalAcl_forbiddenReverseNotInjected_allowedForwardDelivers() {
        val c = Chain(agents(), scope)
        val backend = c.attach("backend")
        val frontend = c.attach("frontend") // a real reader of po-frontend → a wrong inject would land here

        // positive control / non-vacuity: the allowed edge po→backend delivers
        c.router.onHubSend("po", hubSend("po-backend", "allowed: forward-OK"))
        assertTrue(backend.received.any { it.contains("forward-OK") }, "allowed direction delivers")

        // forbidden edge: backend → po-frontend (backend is NOT a member) → 403 at the funnel
        assertFailsWith<ForbiddenException> {
            c.router.onHubSend("backend", hubSend("po-frontend", "intrusion into frontend"))
        }
        assertTrue(
            frontend.received.none { it.contains("intrusion") },
            "a forbidden cross-edge must never be injected into the target's inbound",
        )
        assertTrue(c.hub.channelMessages("po", "po-frontend").isEmpty(), "and nothing is persisted on the forbidden edge")
    }

    // ---- comm.received delivery marker is metadata-only + body needle never reaches the event log ----
    @Test
    fun commReceived_isMetadataOnly_andBodyNeedleNeverLeaksToEventLog() = runBlocking {
        val sink = InMemoryEventSink(ManualTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        val projector = EventProjector(ContextUsageBander(), projectId = DEFAULT_PROJECT_ID)

        val state = HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID)
        val store = InMemoryMessageStore()
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val deliverer = MessageDeliverer(
            { state }, { state.activeProjectId }, sessions, store, InMemoryDeliveryLog(), scope, recorder, projector,
        )
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)
        val router = MediationRouter(SessionRegistry(), hub, recorder, projector)
        val backend = RecordingSession("backend").also { sessions.register(it) }

        // A benign, unique BODY marker (no secret shape: the SecretMasker at the funnel would redact a
        // secret-shaped one — that masking is SecretMasker's own concern). This axis proves the event log
        // is metadata-only: the message BODY, whatever it is, must never appear in comm.sent/comm.received.
        val bodyMarker = "BODY-PAYLOAD-c0ffee-xyz"
        router.onHubSend("po", hubSend("po-backend", "deploy task $bodyMarker", MessageKind.TASK))

        // positive control: the body legitimately reaches the worker session — that IS delivery
        assertTrue(backend.received.any { it.contains(bodyMarker) }, "the body is delivered to the worker session (legit)")

        recorder.stop() // flush the writer queue
        val events = sink.query(EventFilter.ALL, Page(limit = 1000)).events
        val commEvents = events.filter { it.type == EventType.COMM_SENT || it.type == EventType.COMM_RECEIVED }
        // pre-guard / non-vacuity: comm.received WAS actually emitted before we assert it leaks nothing
        assertTrue(
            commEvents.any { it.type == EventType.COMM_RECEIVED },
            "comm.received must be emitted on delivery (else the absence assertion is vacuous)",
        )
        assertFalse(
            commEvents.any { it.detail.toString().contains(bodyMarker) },
            "comm.sent/comm.received are metadata-only — the message body must never reach the event log",
        )
        assertFalse(
            events.any { it.detail.toString().contains(bodyMarker) },
            "no event detail anywhere leaks the message body",
        )
    }

    private class FakeAgentProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        suspend fun feed(line: String) = lines.send(line)
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }
    }
}
