package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireSend
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-142 (S4.3) — the production [KtorWireLink] transport, round-tripped against an in-process WS server
 * (no real network/staging). Proves: the Bearer token reaches the server (auth-first identity), outbound
 * frames are `WireEnvelope{v=1}`-wrapped, and inbound envelopes are unwrapped to [WireFrame]s on `incoming`.
 * The REAL `/ws/hub` round-trip + a real operator-minted token is the staging final-gate (deploy).
 */
class KtorWireLinkTest {

    @Test
    fun roundTrips_wrapsEnvelope_sendsBearerToken_unwrapsInbound() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val seenAuth = AtomicReference<String?>(null)
        val received = CopyOnWriteArrayList<String>()

        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws/hub") {
                    seenAuth.set(call.request.headers["Authorization"])
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val env = CommJson.decodeFromString<WireEnvelope>(frame.readText())
                            (env.frame as? WireSend)?.let { received.add(it.text) }
                            // echo back an inbound WireDeliver so the client's `incoming` unwrap is exercised
                            send(Frame.Text(CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, WireDeliver("echo:${(env.frame as? WireSend)?.text}")))))
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port

        val link = KtorWireLink("ws://localhost:$port/ws/hub", "tok-secret-123", scope)
        link.connect()

        val inbound = CopyOnWriteArrayList<com.tneff.cyppieagents.model.WireFrame>()
        scope.launch { link.incoming.collect { inbound.add(it) } }
        delay(100)

        link.send(WireSend("po-backend", "hello hub"))

        // The server got the wrapped frame WITH the Bearer token; the client unwrapped the echo.
        withTimeout(5000) { while (received.isEmpty() || inbound.none { it is WireDeliver }) delay(10) }
        assertEquals("Bearer tok-secret-123", seenAuth.get(), "the S3 token is sent as a Bearer header")
        assertTrue(received.contains("hello hub"), "the server received the envelope-wrapped WireSend")
        assertEquals("echo:hello hub", (inbound.first { it is WireDeliver } as WireDeliver).text, "inbound envelope unwrapped to a WireFrame")

        link.close()
        server.stop(100, 100)
        scope.cancel()
    }
}
