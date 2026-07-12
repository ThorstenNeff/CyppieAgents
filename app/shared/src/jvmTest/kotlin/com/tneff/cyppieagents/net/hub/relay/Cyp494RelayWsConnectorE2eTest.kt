package com.tneff.cyppieagents.net.hub.relay

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-494 e2e — the real relay dial over a Ktor WS loopback: the dial carries the CYP-506/509 headers
 * (`X-Cyppie-Role: client` + `X-Cyppie-Rendezvous: <id>`), and the framing is **message-preserving** (1 WS binary
 * frame = 1 Noise message, NO length prefix) — what the client sends arrives verbatim, each inbound binary frame
 * is one `receive()`, and a server close is an honest EOF (`null`), never a hang. Uses the embedded-Netty pattern
 * of `EventsWsClientE2eTest`.
 */
class Cyp494RelayWsConnectorE2eTest {

    @Test
    fun dial_carriesRoleAndRendezvousHeaders_andFramingIsMessagePreserving() = runBlocking {
        var role: String? = null
        var rzv: String? = null
        val serverReceived = mutableListOf<ByteArray>()
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/relay") {
                    role = call.request.headers[KtorWsRelayConnector.RELAY_HEADER_ROLE]
                    rzv = call.request.headers[KtorWsRelayConnector.RELAY_HEADER_RENDEZVOUS]
                    val first = incoming.receive()
                    if (first is Frame.Binary) serverReceived += first.data
                    send(Frame.Binary(true, byteArrayOf(9, 8, 7)))
                    send(Frame.Binary(true, byteArrayOf(1, 2, 3, 4, 5)))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val channel = KtorWsRelayConnector(client).open("ws://127.0.0.1:$port/relay", "rzv-abc")
                channel.send(byteArrayOf(42, 43))
                val f1 = withTimeout(10_000) { channel.receive() }
                val f2 = withTimeout(10_000) { channel.receive() }
                val f3 = withTimeout(10_000) { channel.receive() } // after the server close → EOF

                assertEquals(KtorWsRelayConnector.RELAY_ROLE_CLIENT, role, "dialed with X-Cyppie-Role: client")
                assertEquals("rzv-abc", rzv, "dialed with the opaque X-Cyppie-Rendezvous id")
                assertContentEquals(byteArrayOf(42, 43), serverReceived.single(), "sent bytes arrived VERBATIM (no length prefix)")
                assertContentEquals(byteArrayOf(9, 8, 7), f1, "1 inbound binary frame = 1 receive()")
                assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), f2, "each frame preserved as its own message")
                assertNull(f3, "server close → receive() returns null (fail-closed EOF, never a hang)")
                channel.close()
            } finally {
                client.close()
            }
        } finally {
            server.stop()
        }
    }
}
