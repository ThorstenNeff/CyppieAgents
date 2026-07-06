package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.HubMcpTools
import com.tneff.cyppieagents.events.ContextUsageBander
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.hubMcpRoutes
import com.tneff.cyppieagents.support.RecordingSession
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
 *   producer (the Hub MCP server / `HubMcpTools`, CYP-146) → `Hub.postAsAgent` funnel → `onPosted` →
 *   `MessageDeliverer` (CYP-132) → `ConnectorSession.sendTurn` (worker inbound).
 *
 * **CYP-146 reconcile:** the producer leg is the in-process Hub MCP server (the agent's `hub_send` tool),
 * NOT the retired CYP-131 stdout-extraction. T1a drives it at the handler core ([HubMcpTools.send]); T1b
 * drives the FULL wire (`POST /mcp/hub`). Both land in the same funnel→deliver→inject chain.
 *
 * **Complementary, not duplicative.** The halves are already unit-pinned (`HubMcpRoutesTest` producer,
 * `MessageDelivererTest` deliverer, `DelivererBootWiringTest` R2 boot wiring); NONE spans a synthetic
 * `hub_send` all the way to the worker's inbound — the exact composition the RB1 real-run exercises.
 *
 * Determinism: the deliverer drains on `Dispatchers.Unconfined`; `drain` has no real suspension point, so
 * delivery completes by the time the producer call / `register` returns.
 *
 * Non-vacuity (every absence has a positive control in the same fixture):
 *   - remove `MessageDeliverer.drain` `session.sendTurn(...)` → T1a/T1b/T2-forward + comm.received redden (CYP-132 inject).
 *   - flip the `canWrite` check at the funnel → T2 (forbidden reverse) reddens (ACL).
 *   - drop the `comm.received` emit → its pre-guard reddens.
 */
class DeliveryPlumbingChainTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    /** The FULL wired graph: producer (Hub MCP / HubMcpTools) + Hub funnel + deliverer, in-memory. */
    private class Chain(agents: List<Agent>, scope: CoroutineScope) {
        val state = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID)
        val store = InMemoryMessageStore()
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val deliverer = MessageDeliverer({ state }, { state.activeProjectId }, { sessions }, store, InMemoryDeliveryLog(), scope)
        val tokenRegistry = TokenRegistry(
            mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
            operatorToken = "tok-op",
        )
        init {
            hub.onPosted = deliverer::onPosted
            sessions.addRegisterListener(deliverer::onSessionAttached)
        }
        fun attach(agentId: String): RecordingSession = RecordingSession(agentId).also { sessions.register(it) }
    }

    // ---- T1a — producer (HubMcpTools, the /mcp/hub handler core) → worker inbound, server-stamped from ----
    @Test
    fun t1a_mcpHubSend_reachesWorkerInbound_serverStampedFrom() {
        val c = Chain(agents(), scope)
        val backend = c.attach("backend")
        val marker = "DELEGATE-NEEDLE-7f3a"
        assertTrue(backend.received.isEmpty(), "pre-guard: no inbound before the hub_send")

        // The agent emits via the Hub MCP server bound to its identity (here "po") — the new producer leg.
        val posted = HubMcpTools(c.hub, "po").send("po-backend", "build module: $marker", MessageMeta(kind = MessageKind.TASK))

        assertEquals("po", posted.from, "server-stamps the sender as the bound agent (never an input `from`)")
        assertTrue(
            backend.received.any { it.contains(marker) },
            "a synthetic PO hub_send must land in the worker's inbound (producer→funnel→deliver→inject)",
        )
        assertTrue(
            backend.received.any { it.contains("po:") && it.contains("po-backend") },
            "the injected inbound is framed with the server-stamped origin",
        )
    }

    // ---- T1b ⭐ — FULL wire: POST /mcp/hub → extracted/routed → delivered to worker inbound ----
    @Test
    fun t1b_hubSendViaMcpEndpoint_deliveredToWorkerInbound() = testApplication {
        val c = Chain(agents(), scope)
        val backend = c.attach("backend")
        application {
            install(ServerContentNegotiation) { json(CommJson) }
            routing { hubMcpRoutes(c.hub, c.tokenRegistry) }
        }
        val client = createClient { install(ClientContentNegotiation) { json(CommJson) } }
        val marker = "MCP-DELEGATE-d91c"
        assertTrue(backend.received.isEmpty(), "pre-guard: no inbound before the stream emits hub_send")

        val res = client.post("/mcp/hub") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_send","arguments":{"channel":"po-backend","text":"$marker"}}}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(
            backend.received.any { it.contains(marker) },
            "a hub_send via POST /mcp/hub must be routed and DELIVERED to the worker inbound (the full wire path)",
        )
    }

    // ---- T2 — directional ACL: forbidden reverse → no injection; allowed forward delivers (same fixture) ----
    @Test
    fun t2_directionalAcl_forbiddenReverseNotInjected_allowedForwardDelivers() {
        val c = Chain(agents(), scope)
        val backend = c.attach("backend")
        val frontend = c.attach("frontend") // a real reader of po-frontend → a wrong inject would land here

        // positive control / non-vacuity: the allowed edge po→backend delivers
        HubMcpTools(c.hub, "po").send("po-backend", "allowed: forward-OK")
        assertTrue(backend.received.any { it.contains("forward-OK") }, "allowed direction delivers")

        // forbidden edge: backend → po-frontend (backend is NOT a member) → 403 at the funnel
        assertFailsWith<ForbiddenException> {
            HubMcpTools(c.hub, "backend").send("po-frontend", "intrusion into frontend")
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
            { state }, { state.activeProjectId }, { sessions }, store, InMemoryDeliveryLog(), scope, recorder, projector,
        )
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)
        val backend = RecordingSession("backend").also { sessions.register(it) }

        // A benign, unique BODY marker (no secret shape). This axis proves the event log is metadata-only:
        // the message BODY, whatever it is, must never appear in comm.sent/comm.received.
        val bodyMarker = "BODY-PAYLOAD-c0ffee-xyz"
        HubMcpTools(hub, "po").send("po-backend", "deploy task $bodyMarker", MessageMeta(kind = MessageKind.TASK))

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
}
