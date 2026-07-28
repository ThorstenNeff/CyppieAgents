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
import io.ktor.websocket.send
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-908 — an UNDECODABLE `/ws/agent` frame (a schema-skewed / forward-incompatible event) must be a single DROP,
 * not a reconnect. The bug: the decode was unguarded AND `attempt` was reset BEFORE it, so a poison event at
 * `lastSeq+1` threw → re-dial from the unchanged `?since=lastSeq` → the server replayed the same poison → the backoff
 * ladder pinned at the ~250ms floor forever (the CYP-600/289 hammer). Two axes: drop-and-continue, and the
 * poison-doesn't-reset-the-ladder invariant.
 */
class Cyp908PoisonFrameGuardTest {

    /** A frame that fails to decode as StoredAgentEvent (missing every required field) — the "poison". */
    private val poison = """{"garbage":true}"""
    private fun good(seq: Int, tsMs: Long) =
        """{"seq":$seq,"agentId":"backend","projectId":"p","tsMs":$tsMs,"event":{"type":"result","subtype":"success","is_error":false,"uuid":"u$seq"}}"""

    // Axis 1: a poison frame is DROPPED and the socket LIVES — subsequent decodable frames on the same connection
    // still flow. MUT (unguarded decode): the poison throws → the socket is abandoned + re-dialled → a re-replayed
    // poison hammers forever → these two frames never arrive → take(2) times out → RED.
    @Test
    fun poisonFrame_dropped_subsequentGoodFramesStillFlow() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    send(Frame.Text(poison))
                    send(Frame.Text(good(2, 2000)))
                    send(Frame.Text(good(3, 3000)))
                    for (frame in incoming) { /* keep the connection open — no reconnect needed to prove the drop */ }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AgentWsClient(client, "ws://127.0.0.1:$port", "backend", "tok")
                val events = withTimeout(10_000) { ws.events.take(2).toList() }
                // The poison was dropped; the two good frames flowed through unaffected.
                assertEquals(listOf(2000L, 3000L), events.map { it.tsMs })
                assertEquals(listOf(2L, 3L), events.map { it.seq })
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    // Axis 2: a poison-only connection (a frame arrives, but it is UNDECODABLE) is NOT productive → it must NOT reset
    // the backoff ladder. First 3 connects deliver a poison frame then close; the 4th serves a real frame. With the
    // fix the ladder ESCALATES [1,2,3]. MUT (attempt=0 BEFORE the decode): the poison resets it → [1,1,1] floor-hammer.
    @Test
    fun poisonOnlyConnections_doNotResetBackoffLadder() = runBlocking {
        val connects = AtomicInteger(0)
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                webSocket("/ws/agent") {
                    if (connects.incrementAndGet() <= 3) {
                        send(Frame.Text(poison)) // a frame arrives, but it is undecodable → not a productive frame
                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "poison-only, no productive frame"))
                    } else {
                        send(Frame.Text(good(1, 1000)))
                        for (frame in incoming) { /* keep open */ }
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val attempts = Collections.synchronizedList(mutableListOf<Int>())
                val ws = AgentWsClient(
                    client, "ws://127.0.0.1:$port", "backend", "tok",
                    backoff = Backoff(initialMs = 1L, maxMs = 1L),
                    reconnectDelay = { attempts.add(it) },
                )
                withTimeout(15_000) { ws.events.take(1).toList() } // completes on the 4th (productive) connect
                assertEquals(
                    listOf(1, 2, 3),
                    attempts.toList(),
                    "a poison-only connection must NOT reset the ladder — it climbs, not floor-hammers",
                )
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
