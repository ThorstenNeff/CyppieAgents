package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.SystemEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-6 swap — e2e proof that [AgentWsClient] actually opens a real WebSocket, receives masked
 * [com.tneff.cyppieagents.model.StreamJsonEvent] frames, and decodes them. Runs against an embedded
 * Ktor server shaped like `/ws/agent` (CYP-13), so it proves the socket path without needing the
 * real backend running. Frame codec is additionally unit-tested in AgentWsCodecTest.
 */
class AgentWsClientE2eTest {

    @Test
    fun liveSocket_receivesAndDecodesFrames() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/agent") {
                    // CYP-198/204: frames are StoredAgentEvent wrappers ({seq, …, event}); the client reads the
                    // wrapper, tracks seq, and (CYP-335) surfaces the WHOLE wrapper so `tsMs` reaches the renderer.
                    // The two frames carry DIFFERENT tsMs, so a client that dropped or confused them is visible.
                    send(Frame.Text("""{"seq":1,"agentId":"backend","projectId":"p","tsMs":1000,"event":{"type":"system","subtype":"init","uuid":"u1","model":"claude-opus-4-8"}}"""))
                    send(Frame.Text("""{"seq":2,"agentId":"backend","projectId":"p","tsMs":2000,"event":{"type":"assistant","uuid":"u2","message":{"role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"hallo"}]}}}"""))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = AgentWsClient(client, "ws://127.0.0.1:$port", agentId = "backend", token = "tok")
                // CYP-204: `events` now auto-reconnects (never completes), so take the two pushed frames rather
                // than toList(). The server close triggers a reconnect whose replay the client dedups by seq.
                val events = withTimeout(10_000) { ws.events.take(2).toList() }
                assertEquals(2, events.size, "expected the two pushed frames")
                assertTrue(events[0].event is SystemEvent)
                assertTrue(events[1].event is AssistantEvent)
                // CYP-335: the server's stamp survives decoding — it is not dropped with the envelope.
                assertEquals(1_000L, events[0].tsMs)
                assertEquals(2_000L, events[1].tsMs)
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
