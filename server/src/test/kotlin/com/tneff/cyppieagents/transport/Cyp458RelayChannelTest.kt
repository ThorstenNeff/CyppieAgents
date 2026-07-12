package com.tneff.cyppieagents.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-458 (S2) — the [RelayChannelOverWebSocket] L0 adapter against a real Ktor client↔server WebSocket: each binary
 * frame is exactly one Noise message (message-preserving, no length prefix, §4.3), round-trips both ways, and a relay
 * close yields `receive() == null` (fail-closed). The bounded (≤8) buffer is exercised implicitly by the pump.
 */
class Cyp458RelayChannelTest {

    @Test
    fun binaryFrameRoundTrips_andCloseYieldsNull() = runBlocking {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                // A minimal echo "relay": reflect each binary frame back verbatim.
                webSocket("/relay") {
                    for (f in incoming) if (f is Frame.Binary) send(Frame.Binary(true, f.readBytes()))
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }
        try {
            val session = client.webSocketSession("ws://127.0.0.1:$port/relay")
            val relay = RelayChannelOverWebSocket(session)

            relay.send("frame-1".encodeToByteArray())
            assertEquals("frame-1", relay.receive()?.decodeToString(), "one binary frame = one Noise message, round-trips")
            relay.send("frame-2".encodeToByteArray())
            assertEquals("frame-2", relay.receive()?.decodeToString(), "frames preserve their boundaries in order")

            relay.close()
            assertNull(relay.receive(), "after close, receive() is null (fail-closed)")
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }
}
