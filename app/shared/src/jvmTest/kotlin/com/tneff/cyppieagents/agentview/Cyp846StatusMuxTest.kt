package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.BusyStatus
import com.tneff.cyppieagents.model.LifecycleStatus
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StatusFrame
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalStatus
import com.tneff.cyppieagents.model.TokenUsageStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.ApplicationCall
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-846 — the load-bearing teeth for the **Compose status-mux** ([StatusMuxClient]): ONE `/ws/status` socket
 * carrying the four content-free status feeds as a discriminated [StatusFrame] union, replacing the four separate
 * `…LiveSource` sockets. These consolidate the four per-feed CYP-819 revoke teeth + the four per-feed socket-decode
 * teeth into one muxed socket, and add the CYP-846 addendum (malformed = transient) + the exhaustive demux.
 *
 * Not render-based (embedded WS server + plain flow collects) → no headless Compose-UI hang. `onRevoked` drives the
 * visible covering banner + indicator demotion, proven separately in the CYP-819 render teeth.
 */
class Cyp846StatusMuxTest {

    private fun scope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun frame(f: StatusFrame): Frame.Text =
        Frame.Text(CommJson.encodeToString(StatusFrame.serializer(), f))

    /** Stand up a `/ws/status` server whose socket body is [body]; run [block] with the client + port + revoke flag. */
    private fun withStatus(
        connects: AtomicInteger = AtomicInteger(0),
        body: suspend WebSocketServerSession.(connectionNo: Int) -> Unit,
        block: suspend CoroutineScope.(client: HttpClient, wsBase: String, revoked: () -> Boolean, mark: () -> Unit) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing { serverWebSocket("/ws/status") { body(connects.incrementAndGet()) } }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                var revoked = false
                block(client, "ws://127.0.0.1:$port", { revoked }, { revoked = true })
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    /**
     * (AC#3) A **1008 (VIOLATED_POLICY)** revoke on the muxed socket is **TERMINAL**: `onRevoked` fires AND the dead
     * token is **never re-dialled** (the connection count stays at 1 — the anti-hammer proof, the CYP-289 loop class).
     *
     * Non-vacuity: reverting the `if (isAccessRevoked(closeCode)) send(Revoked)` guard in [statusFeed] (or the
     * `takeWhile` in [terminalOnRevoke]) makes the 1008 a plain close → `.reconnecting()` re-dials forever → the
     * connection count climbs past 1 AND `onRevoked` never fires → both asserts RED.
     */
    @Test
    fun status_1008_isTerminal_signalsRevoke_noHammer() {
        val connects = AtomicInteger(0)
        withStatus(
            connects = connects,
            body = { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked")) },
        ) { client, ws, revoked, mark ->
            val muxScope = scope()
            try {
                val mux = StatusMuxClient(client, ws, ws, "bad", onRevoked = mark, scope = muxScope)
                val job = muxScope.launch { mux.lifecycle.events().collect {} } // keep a subscriber → drives the socket
                withTimeout(10_000) { while (!revoked()) delay(20) } // a 1008 MUST fire onRevoked
                delay(500) // settle: a hammering feed would re-dial in this window
                assertEquals(1, connects.get(), "a 1008 is terminal → the dead token must NOT be re-dialled (no hammer)")
                assertTrue(revoked(), "a 1008 must fire onRevoked (the covering-revoke signal)")
                job.cancel()
            } finally {
                muxScope.cancel()
            }
        }
    }

    /**
     * (AC#3, discrimination) The NON-revoked side, positively toothed: a **NON-1008** close (NORMAL/1000, a transient
     * drop) must NOT fire `onRevoked` AND the feed must **re-subscribe** — proven POSITIVELY by a **second connection**.
     *
     * Non-vacuity: mutation `isAccessRevoked(_) → true` makes the NORMAL close emit `Revoked` → `onRevoked` fires +
     * `takeWhile` terminates → NO reconnect → `connects == 2` times out AND `revoked` is true → RED.
     */
    @Test
    fun status_nonRevokedClose_isTransient_reconnects_noRevoke() {
        val connects = AtomicInteger(0)
        withStatus(
            connects = connects,
            body = { n ->
                if (n == 1) close(CloseReason(CloseReason.Codes.NORMAL, "bye")) // NON-1008 transient drop
                else awaitCancellation() // stay open on the reconnect → the count settles at 2 (positive proof)
            },
        ) { client, ws, revoked, mark ->
            val muxScope = scope()
            try {
                val mux = StatusMuxClient(client, ws, ws, "tok", onRevoked = mark, scope = muxScope)
                val job = muxScope.launch { mux.lifecycle.events().collect {} }
                withTimeout(10_000) { while (connects.get() < 2) delay(20) } // POSITIVE reconnect proof (2nd connection)
                assertEquals(2, connects.get(), "a NON-1008 close is transient → the feed must RE-SUBSCRIBE")
                assertFalse(revoked(), "a NON-1008 close must NOT latch a revoke (an over-eager isAccessRevoked = false-positive)")
                job.cancel()
            } finally {
                muxScope.cancel()
            }
        }
    }

    /**
     * (AC-addendum, Backend2 skew) A **malformed / undecodable [StatusFrame]** is **TRANSIENT, never terminal**: the
     * frame is dropped and the SAME socket LIVES — a subsequent VALID frame still arrives on connection #1, `onRevoked`
     * never fires, and the socket is never re-dialled. This is the deliberate OPPOSITE of `/ws/comm`'s terminal
     * `ProtocolSkew` (CYP-786); web-ts F-A5-2/CYP-834 parity.
     *
     * Non-vacuity: replacing the resilient decode (`runCatching{…}.getOrNull()`) with a throwing decode
     * (`CommJson.decodeFromString(…)`) makes the garbage frame throw → the frame loop dies → the valid frame is never
     * read on the live socket (and `.reconnecting()` re-dials, count climbs) → the "valid arrives on conn #1" assert RED.
     */
    @Test
    fun malformedFrame_isTransient_channelLives() {
        val connects = AtomicInteger(0)
        val good = AgentRunStateEvent("backend", AgentRunState.RUNNING)
        withStatus(
            connects = connects,
            body = {
                send(Frame.Text("{\"type\":\"lifecycle\",\"event\":\"NOT-AN-OBJECT\"}")) // schema-violating garbage
                send(frame(LifecycleStatus(good)))                                       // …then a VALID frame
                awaitCancellation()                                                       // keep the socket open
            },
        ) { client, ws, revoked, mark ->
            val muxScope = scope()
            try {
                val mux = StatusMuxClient(client, ws, ws, "op", onRevoked = mark, scope = muxScope)
                val received = withTimeout(10_000) { mux.lifecycle.events().first() } // the VALID event, after the garbage
                assertEquals(AgentLifecycleEvent("backend", AgentLifecycleState.RUNNING), received,
                    "a malformed frame must be skipped and the SAME socket keep delivering (transient, not terminal)")
                assertEquals(1, connects.get(), "the socket must LIVE through a malformed frame — never re-dialled")
                assertFalse(revoked(), "a malformed frame is a single-drop, never a revoke")
            } finally {
                muxScope.cancel()
            }
        }
    }

    /**
     * (AC#1/#4) The **demux**: one muxed socket carries all four variants, and each is routed to ITS projection only,
     * decoding the REAL `:core` payload verbatim off the shared socket (this replaces the four per-feed socket-decode
     * E2E teeth). The exhaustive `when` in [StatusMuxClient.cache] is enforced at COMPILE time (a fifth variant fails to
     * compile there — the assertNever twin), so routing correctness + compile-exhaustiveness together are the 1:1 proof.
     */
    @Test
    fun demux_routesEachVariantToItsProjection() {
        withStatus(
            body = {
                send(frame(LifecycleStatus(AgentRunStateEvent("backend", AgentRunState.RUNNING))))
                send(frame(TokenUsageStatus(AgentTokenUsageEvent("backend", 137_000))))
                send(frame(BusyStatus(AgentBusyStateEvent("backend", true))))
                send(frame(TerminalStatus(AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 1000L))))
                awaitCancellation()
            },
        ) { client, ws, _, _ ->
            val muxScope = scope()
            try {
                val mux = StatusMuxClient(client, ws, ws, "op", scope = muxScope)
                val lifecycle = CompletableDeferred<AgentLifecycleEvent>()
                val token = CompletableDeferred<AgentTokenUsageEvent>()
                val busy = CompletableDeferred<AgentBusyStateEvent>()
                val terminal = CompletableDeferred<AgentTerminalControlEvent>()
                muxScope.launch { lifecycle.complete(mux.lifecycle.events().first()) }
                muxScope.launch { token.complete(mux.tokenUsage.events().first()) }
                muxScope.launch { busy.complete(mux.busy.events().first()) }
                muxScope.launch { terminal.complete(mux.terminal.events().first()) }
                withTimeout(10_000) {
                    assertEquals(AgentLifecycleEvent("backend", AgentLifecycleState.RUNNING), lifecycle.await(),
                        "the lifecycle variant routes to lifecycle.events()")
                    assertEquals(AgentTokenUsageEvent("backend", 137_000), token.await(),
                        "the tokenUsage variant routes to tokenUsage.events()")
                    assertEquals(AgentBusyStateEvent("backend", true), busy.await(),
                        "the busy variant routes to busy.events()")
                    assertEquals(AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 1000L), terminal.await(),
                        "the terminal variant routes to terminal.events() (holder + since carried verbatim)")
                }
            } finally {
                muxScope.cancel()
            }
        }
    }

    /**
     * (carried from the folded lifecycle E2E) `snapshot()` FAILS CLOSED to empty on an unreachable server — the header
     * stays UNKNOWN, never crashes the window. Mutation: `catch (e: Throwable) -> throw e` → this is the only test RED.
     */
    @Test
    fun snapshot_unreachableServer_failsClosedToEmpty() = runBlocking {
        val client = HttpClient(CIO)
        val muxScope = scope()
        try {
            val mux = StatusMuxClient(client, "http://127.0.0.1:1", "ws://127.0.0.1:1", "op", scope = muxScope)
            val snap = withTimeout(10_000) { mux.lifecycle.snapshot() }
            assertEquals(emptyMap(), snap, "an unreachable server must fail-closed to an empty snapshot, not throw")
        } finally {
            muxScope.cancel()
            client.close()
        }
    }

    /**
     * (carried from the folded lifecycle E2E) `snapshot()` carries the credential (CC1/CYP-179 — `GET /api/agents` is
     * gated). Mutation: drop the Authorization header on the GET → server 401s → snapshot() fails closed to empty → RED.
     */
    @Test
    fun snapshot_sendsCredential_soGatedServerAccepts() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/agents") { respondAgentsGatedByBearer(call) }
            }
        }
        server.start(wait = false)
        val muxScope = scope()
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val mux = StatusMuxClient(client, "http://127.0.0.1:$port", "ws://127.0.0.1:$port", "op", scope = muxScope)
                val snap = withTimeout(10_000) { mux.lifecycle.snapshot() }
                assertEquals(mapOf("backend" to AgentLifecycleState.RUNNING), snap)
            } finally {
                client.close()
            }
        } finally {
            muxScope.cancel()
            server.stop(100, 200)
        }
    }

    private suspend fun respondAgentsGatedByBearer(call: ApplicationCall) {
        if (call.request.header(HttpHeaders.Authorization) != "Bearer op") {
            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
        } else {
            call.respondText(
                CommJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Agent.serializer()),
                    listOf(Agent("backend", "Backend", Role.WORKER, "backend", AgentRunState.RUNNING)),
                ),
                ContentType.Application.Json,
            )
        }
    }
}
