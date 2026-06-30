package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryDeliveryLog
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.mediation.MessageDeliverer
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus.AVAILABLE
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.WireRateLimiter
import com.tneff.cyppieagents.routing.hubWireRoutes
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * E2.5 / CYP-141 — Remote-Connector Server-Accept. Two in-process wire clients stand in for two machines;
 * the REAL CYP-132 [MessageDeliverer] is wired to the hub. RA1–RA5 + the 3 RCs (RC1 send-lock coverage,
 * RC2 cursor-after-success, RC3 reconnect compare-and-remove).
 */
class RemoteAcceptTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val provider = ProviderInfo("claude", "Claude")
    private fun allAvailable() = Capabilities(AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, AVAILABLE, ConnectorKind.STREAM_JSON)
    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
    )
    private fun registry() = TokenRegistry(
        mapOf("tok-po" to "po", "tok-backend" to "backend", "tok-frontend" to "frontend"),
        operatorToken = "tok-op",
    )

    private class RemoteFx(val hub: Hub, val store: InMemoryMessageStore, val sessions: ConnectorSessions)

    /** Wire the REAL deliverer to the hub + the /ws/hub route — exactly the boot wiring (CYP-132 + CYP-141). */
    private fun ApplicationTestBuilder.installRemote(): RemoteFx {
        val store = InMemoryMessageStore()
        val state = HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID)
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val deliverer = MessageDeliverer({ hub.state }, { hub.state.activeProjectId }, sessions, store, InMemoryDeliveryLog(), scope)
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)
        application {
            install(WebSockets)
            routing { hubWireRoutes(hub, registry(), CapabilityRegistry(), ProviderRegistry(), WireRateLimiter(), sessions, com.tneff.cyppieagents.events.EventRecorder(com.tneff.cyppieagents.events.InMemoryEventSink(com.tneff.cyppieagents.events.SystemTimeSource()), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)), { "default" }) }
        }
        return RemoteFx(hub, store, sessions)
    }

    private fun frame(f: WireFrame, v: Int = 1) = CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(v, f))
    private suspend fun DefaultClientWebSocketSession.sendFrame(f: WireFrame) = send(Frame.Text(frame(f)))
    private suspend fun DefaultClientWebSocketSession.recv(): WireFrame =
        CommJson.decodeFromString<WireEnvelope>((incoming.receive() as Frame.Text).readText()).frame
    private suspend fun DefaultClientWebSocketSession.handshake() {
        sendFrame(WireHello(allAvailable(), provider)); assertIs<WireAck>(recv())
    }
    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }

    // ---------- RA1 ⭐ cross-machine delivery (the PRD-11 AC) ----------
    @Test
    fun ra1_remoteWorkerReceivesADelegatedTaskOverTheWire() = testApplication {
        installRemote()
        val client = wsClient(this)
        val workerReady = CompletableDeferred<Unit>()
        val workerGotTask = CompletableDeferred<String>()
        val workerJob = scope.launch {
            client.webSocket("/ws/hub?token=tok-backend") {
                handshake(); workerReady.complete(Unit)
                val f = recv()
                if (f is WireDeliver) workerGotTask.complete(f.text)
            }
        }
        workerReady.await() // the worker is registered before the PO delegates
        client.webSocket("/ws/hub?token=tok-po") {
            handshake()
            sendFrame(WireSend("po-backend", "TASK-needle-7b3c", MessageKind.TASK))
            assertIs<WireAck>(recv())
        }
        val delivered = withTimeout(5_000) { workerGotTask.await() }
        assertTrue(delivered.contains("TASK-needle-7b3c"), "the remote worker received the delegated task over the wire")
        assertTrue(delivered.contains("po:"), "framed as an inbound from the PO")
        workerJob.cancel()
    }

    // ---------- RA2 ⭐ ACL-correct inbound: no cross-project leak (re-pins CYP-132-R1 on the wire egress) ----------
    @Test
    fun ra2_remoteInboundIsAclFiltered_noForeignProjectLeak() = testApplication {
        val fx = installRemote()
        // Same channel "po-backend": two in-project messages sandwich a FOREIGN-project one (unique needle).
        fx.store.append(Message("m1", "po-backend", "po", "inproj-A-1aa", ts = 1)) // projectId default = active
        fx.store.append(Message("m2", "po-backend", "po", "FOREIGN-2bb", ts = 2, projectId = "projB"))
        fx.store.append(Message("m3", "po-backend", "po", "inproj-B-3cc", ts = 3))
        val client = wsClient(this)
        val bodies = mutableListOf<String>()
        client.webSocket("/ws/hub?token=tok-backend") {
            handshake()
            // drain replays the two in-project messages (the foreign one is dropped by visibleMessages).
            bodies.add(assertIs<WireDeliver>(withTimeout(5_000) { recv() }).text)
            bodies.add(assertIs<WireDeliver>(withTimeout(5_000) { recv() }).text)
        }
        assertTrue(bodies.any { it.contains("inproj-A-1aa") } && bodies.any { it.contains("inproj-B-3cc") }, "in-project messages delivered (pre-guard)")
        assertTrue(bodies.none { it.contains("FOREIGN-2bb") }, "a foreign-project message on a reused channelId must NOT reach the remote")
    }

    // ---------- RA3 dedup / at-least-once over reconnect ----------
    @Test
    fun ra3_reconnectDoesNotRedeliverAnAlreadyDeliveredMessage() = testApplication {
        val fx = installRemote()
        fx.hub.postAsAgent("po", "po-backend", "msg1-already") // pending
        val client = wsClient(this)
        client.webSocket("/ws/hub?token=tok-backend") { // conn1 receives msg1 → marked delivered
            handshake()
            assertTrue(assertIs<WireDeliver>(withTimeout(5_000) { recv() }).text.contains("msg1-already"))
        } // conn1 closes
        // Wait for conn1's server-side session to unregister (close is async) so msg2 isn't delivered to the
        // stale connection — then it is genuinely pending for the reconnect (the dedup is what we're testing).
        withTimeout(5_000) { while (fx.sessions.session("backend") != null) kotlinx.coroutines.delay(10) }
        fx.hub.postAsAgent("po", "po-backend", "msg2-fresh") // pending; msg1 already delivered
        client.webSocket("/ws/hub?token=tok-backend") { // conn2 (reconnect)
            handshake()
            // first delivery is msg2 — NOT a re-delivery of msg1 (DeliveryLog dedup over reconnect).
            assertTrue(assertIs<WireDeliver>(withTimeout(5_000) { recv() }).text.contains("msg2-fresh"), "reconnect must not re-deliver msg1")
        }
    }

    // ---------- RA5 reconnect race (RC3): new connection registered, old one's close must not orphan it ----------
    @Test
    fun ra5_oldConnectionClosingAfterReconnect_doesNotOrphanTheNewSession() = testApplication {
        val fx = installRemote()
        val client = wsClient(this)
        val ready1 = CompletableDeferred<Unit>()
        val ready2 = CompletableDeferred<Unit>()
        val close1 = CompletableDeferred<Unit>()
        val conn2GotTask = CompletableDeferred<String>()

        val job1 = scope.launch {
            client.webSocket("/ws/hub?token=tok-backend") { handshake(); ready1.complete(Unit); close1.await() }
        }
        ready1.await()
        val job2 = scope.launch {
            client.webSocket("/ws/hub?token=tok-backend") { // reconnect: replaces conn1 as the current session
                handshake(); ready2.complete(Unit)
                val f = recv()
                if (f is WireDeliver) conn2GotTask.complete(f.text)
            }
        }
        ready2.await()       // conn2 is now the registered session for "backend"
        close1.complete(Unit) // conn1 closes → its finally fires removeIfSame(conn1) → must be a no-op
        job1.join()
        fx.hub.postAsAgent("po", "po-backend", "after-reconnect-9f1a") // → must reach conn2
        val delivered = withTimeout(5_000) { conn2GotTask.await() }
        assertTrue(delivered.contains("after-reconnect-9f1a"), "the reconnected session keeps receiving; old close didn't orphan it")
        job2.cancel()
    }

    // ---------- RC2 ⭐ failure-replay: a wire push that THROWS (closed mid-drain) is NOT marked → re-delivered ----------
    // CYP-132 was built for local sessions (sendTurn never throws); the wire sendTurn CAN throw. It must
    // PROPAGATE so the deliverer's markDelivered (after-success) doesn't advance → re-delivered on reconnect.
    @Test
    fun rc2_aFailedWirePush_isNotMarkedDelivered_andReDeliversOnReconnect() = testApplication {
        // Deliverer + sessions wired directly (no WS client) — drives the throw path deterministically.
        val store = InMemoryMessageStore()
        val state = HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID)
        val hub = Hub(state, store)
        val sessions = ConnectorSessions()
        val deliverer = MessageDeliverer({ hub.state }, { hub.state.activeProjectId }, sessions, store, InMemoryDeliveryLog(), scope)
        hub.onPosted = deliverer::onPosted
        sessions.addRegisterListener(deliverer::onSessionAttached)

        // A remote session whose push FAILS (closed WS). sendTurn must propagate the throw.
        val attempted = CompletableDeferred<Unit>()
        sessions.register(
            com.tneff.cyppieagents.routing.WireConnectorSession(
                "backend",
                sendDeliver = { attempted.complete(Unit); throw RuntimeException("ws closed mid-drain") },
                closeWs = {},
            ),
        )
        hub.postAsAgent("po", "po-backend", "task-rc2-c0de") // drain → sendTurn throws → NOT marked
        withTimeout(5_000) { attempted.await() } // the failing push happened (and threw → cursor not advanced)

        // The reconnect: a recording session replaces it → onSessionAttached → re-delivers the unmarked task.
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        sessions.register(
            com.tneff.cyppieagents.routing.WireConnectorSession("backend", sendDeliver = { got.add(it) }, closeWs = {}),
        )
        withTimeout(5_000) { while (got.none { it.contains("task-rc2-c0de") }) kotlinx.coroutines.delay(10) }
        assertTrue(got.any { it.contains("task-rc2-c0de") }, "a push that failed mid-drain must be re-delivered on reconnect (at-least-once)")
    }
}
