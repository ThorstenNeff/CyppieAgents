package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.net.Backoff
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-598-B / CYP-600 — a **stopped agent** (the server accept-then-immediately-closes the `/ws/agent`, 0 frames)
 * must ESCALATE the reconnect backoff, not hammer at the floor. The live bug: `attempt` was reset on the bare WS
 * upgrade → pinned at the 250ms floor → 311k reconnects that hogged the shared client runtime and starved the idle
 * agents. The fix resets the ladder only on a PRODUCTIVE frame — so a 0-frame connection climbs the ladder.
 */
class Cyp598BackoffEscalationTest {

    @Test
    fun zeroFrameConnections_escalateTheBackoff_notFloorHammer() = runBlocking {
        val connects = AtomicInteger(0)
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    // First 3 connects: accept then immediately close with NO frame (a stopped agent). 4th: serve one
                    // real frame + stay open (a productive connection) so the client's events flow completes take(1).
                    if (connects.incrementAndGet() <= 3) {
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "stopped-agent, no frames"))
                    } else {
                        send(Frame.Text("""{"seq":1,"agentId":"backend","projectId":"p","tsMs":1000,"event":{"type":"result","subtype":"success","is_error":false,"uuid":"u1"}}"""))
                        for (frame in incoming) { /* keep open */ }
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                // Recording delay seam: capture the backoff `attempt` for each reconnect WITHOUT real waiting.
                val attempts = Collections.synchronizedList(mutableListOf<Int>())
                val ws = AgentWsClient(
                    client, "ws://127.0.0.1:$port", "backend", "tok",
                    backoff = Backoff(initialMs = 1L, maxMs = 1L),
                    reconnectDelay = { attempts.add(it) },
                )
                withTimeout(15_000) { ws.events.take(1).toList() } // completes on the 4th (productive) connect
                // Reddening mutation: reset `attempt = 0` on the bare upgrade (the old code) ⇒ every 0-frame connect
                // resets ⇒ attempts == [1, 1, 1] (the 250ms floor-hammer). The fix ⇒ they ESCALATE [1, 2, 3].
                assertEquals(listOf(1, 2, 3), attempts.toList(), "0-frame (stopped-agent) reconnects must climb the ladder, not hammer the floor")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
