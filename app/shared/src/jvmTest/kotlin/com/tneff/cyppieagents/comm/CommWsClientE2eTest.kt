package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
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
 * CYP-21 live swap — e2e proof that [CommWsClient] opens a real `/ws/comm` socket, decodes the
 * `:core` [CommWsServerEvent] frames (CYP-18 contract: ChannelsEvent snapshot then live messages)
 * and maps them onto the UI [CommLiveEvent] seam, bracketed by Connected/Disconnected.
 */
class CommWsClientE2eTest {

    @Test
    fun liveSocket_snapshotThenMessage_mapsToCommLiveEvents() = runBlocking {
        val channelsFrame = CommJson.encodeToString(
            CommWsServerEvent.serializer(),
            ChannelsEvent(listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))),
        )
        val messageFrame = CommJson.encodeToString(
            CommWsServerEvent.serializer(),
            MessageEvent(Message("m1", "po-frontend", "frontend", "hi PO", 1L)),
        )

        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/comm") {
                    send(Frame.Text(channelsFrame))
                    send(Frame.Text(messageFrame))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = CommWsClient(client, "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { ws.events().toList() }

                assertEquals(4, events.size, "Connected, ChannelsChanged, MessageReceived, Disconnected")
                assertTrue(events[0] is CommLiveEvent.Connected)
                val channels = events[1] as CommLiveEvent.ChannelsChanged
                assertEquals("po-frontend", channels.channels.single().id)
                val message = events[2] as CommLiveEvent.MessageReceived
                assertEquals("m1", message.message.id)
                assertTrue(events[3] is CommLiveEvent.Disconnected)
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
