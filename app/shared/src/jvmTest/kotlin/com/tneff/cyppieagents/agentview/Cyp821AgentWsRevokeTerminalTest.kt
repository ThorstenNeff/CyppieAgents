package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.ConnectionStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-821 (the AgentWsClient sibling of CYP-819) — the per-agent **transcript** socket (`/ws/agent`) was the last
 * client feed that `.reconnecting()`-looped the **dead token** on a **1008 (VIOLATED_POLICY)** auth-revoke (the
 * CYP-289 background hammer; no UX symptom since CYP-819 already suppresses the `↻` chip via `statusRevoked`, but a
 * real resource leak). It now reads the close code (`WsClose.isAccessRevoked`) and makes a 1008 **terminal** — the
 * same contract the four status feeds got in CYP-819.
 *
 * Non-vacuity, two directions (embedded WS server, no render → no headless hang):
 *  - [revoke1008_isTerminal_noReconnectHammer]: a 1008 ENDS `events` (so `toList()` returns instead of looping
 *    forever) and holds `connection` at DISCONNECTED. Mutation — remove `if (isAccessRevoked(readCloseCode()))
 *    revoked = true` → the loop re-dials the dead token forever → `withTimeout` throws → RED.
 *  - [normalClose_stillReconnects]: a NORMAL close still reconnects (connect #2 happens), proving the terminal
 *    branch is 1008-SPECIFIC and did not turn every close into a stop.
 */
class Cyp821AgentWsRevokeTerminalTest {

    @Test
    fun revoke1008_isTerminal_noReconnectHammer() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing { serverWebSocket("/ws/agent") { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "revoked")) } }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AgentWsClient(client, "ws://127.0.0.1:$port", agentId = "backend", token = "bad", reconnectDelay = {})
                // Terminates iff the 1008 breaks the reconnect loop; would hang forever (the hammer) otherwise.
                val events = withTimeout(10_000) { ws.events.toList() }
                assertTrue(events.isEmpty(), "a 1008 delivered no frames")
                assertEquals(ConnectionStatus.DISCONNECTED, ws.connection.value, "terminal revoke → DISCONNECTED, never a false reconnecting")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun normalClose_stillReconnects() = runBlocking {
        val connects = AtomicInteger(0)
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/agent") {
                    val n = connects.incrementAndGet()
                    if (n == 1) close(CloseReason(CloseReason.Codes.NORMAL, "bye")) // transient → the client must reconnect
                    else awaitCancellation() // stay open on the reconnect so the count settles at 2
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AgentWsClient(client, "ws://127.0.0.1:$port", agentId = "backend", token = "", reconnectDelay = {})
                val job = launch { ws.events.collect {} } // drive the reconnect loop
                withTimeout(10_000) { while (connects.get() < 2) delay(20) }
                assertEquals(2, connects.get(), "a NORMAL close is transient — the transcript socket must reconnect (only 1008 is terminal)")
                job.cancel()
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
