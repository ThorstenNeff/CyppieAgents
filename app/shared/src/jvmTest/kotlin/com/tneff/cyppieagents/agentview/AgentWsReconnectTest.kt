package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.ConnectionStatus
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-204 — the reconnect/replay/dedup AC for [AgentWsClient], end-to-end against an embedded Ktor `/ws/agent`
 * that models CYP-198: it serves StoredAgentEvent frames and honours `?since`. The first connect (no `since`)
 * replays the WHOLE history seq 1..3, then the socket DROPS; the client auto-reconnects with `?since=3` and the
 * server replays INCLUSIVE (3,4,5). The AC, teethed:
 *  - **Reconnect shows history (non-vacuous):** the window collects all 5 events across the drop.
 *  - **No dups:** the re-sent seq 3 is dropped by the client's cursor-dedup → 5 events, not 6.
 *  - **Auto-reconnect + cursor-resume:** exactly 2 connections; the 2nd carried `since=3` (first carried none).
 */
class AgentWsReconnectTest {

    private fun frame(seq: Long): String =
        """{"seq":$seq,"agentId":"backend","projectId":"p","tsMs":0,"event":{"type":"result","subtype":"success","is_error":false,"uuid":"u-$seq"}}"""

    @Test
    fun reconnect_resumesFromCursor_replaysHistory_noDups() = runBlocking {
        val sinces = Collections.synchronizedList(mutableListOf<String?>())
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    val since = call.request.queryParameters["since"]
                    sinces.add(since)
                    if (since == null) {
                        // First connect: replay the whole history, then DROP (simulate a lost socket).
                        for (s in 1..3) send(Frame.Text(frame(s.toLong())))
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "drop"))
                    } else {
                        // Reconnect: replay INCLUSIVE from the cursor (3,4,5) — the client must dedup the re-sent 3.
                        for (s in 3..5) send(Frame.Text(frame(s.toLong())))
                        for (frame in incoming) { /* keep the socket open (no further drop) */ }
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AgentWsClient(
                    client, "ws://127.0.0.1:$port", "backend", "tok",
                    backoff = Backoff(initialMs = 10L, maxMs = 20L),
                )
                val collected = withTimeout(15_000) { ws.events.take(5).toList() }
                assertEquals(5, collected.size, "history replayed across the drop, cursor-dedup dropped the re-sent seq 3")
                assertEquals(2, sinces.size, "exactly one auto-reconnect")
                assertEquals(null, sinces[0], "first connect omits ?since → the server replays the whole history")
                assertEquals("3", sinces[1], "reconnect resumes from the seq cursor (?since=3)")
                assertEquals(ConnectionStatus.LIVE, ws.connection.value, "reconnected → LIVE again")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
