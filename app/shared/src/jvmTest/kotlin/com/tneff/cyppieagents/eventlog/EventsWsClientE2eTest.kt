package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.SubscribeEvents
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
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
 * CYP-40 live swap — e2e proof that [EventsWsClient] opens `/ws/events`, sends a [SubscribeEvents]
 * frame (event-type axis on the wire as `eventType`, NOT `type`), decodes `:core`
 * [EventsWsServerEvent] frames onto the [EventLiveEvent] seam, and — the fail-closed property —
 * maps a **1008** close to [EventLiveEvent.AccessRevoked] (not a generic offline). Mirrors
 * `CommWsClientE2eTest`.
 */
class EventsWsClientE2eTest {

    private fun ev(seq: Long) = Event(
        id = "e$seq", ts = seq, seq = seq, agentId = "backend", teamId = "t",
        type = EventType.TURN_START, severity = Severity.INFO,
    )

    @Test
    fun liveSocket_pushesEvents_andSubscribeCarriesEventType() = runBlocking {
        var subscribeFrame: String? = null
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/events") {
                    val first = incoming.receive()
                    if (first is Frame.Text) subscribeFrame = first.readText()
                    send(Frame.Text(CommJson.encodeToString(EventsWsServerEvent.serializer(), EventPushed(ev(1)))))
                    send(Frame.Text(CommJson.encodeToString(EventsWsServerEvent.serializer(), EventPushed(ev(2)))))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = EventsWsClient(client, "ws://127.0.0.1:$port", token = "op")
                val events = withTimeout(10_000) { ws.events(EventFilter(type = EventType.TURN_START)).toList() }

                assertEquals(4, events.size, "Connected, Received x2, Disconnected")
                assertTrue(events[0] is EventLiveEvent.Connected)
                assertEquals(1L, (events[1] as EventLiveEvent.Received).event.seq)
                assertEquals(2L, (events[2] as EventLiveEvent.Received).event.seq)
                assertTrue(events[3] is EventLiveEvent.Disconnected)

                // The subscribe frame carried the type filter as `eventType` (not `type`).
                val frameText = subscribeFrame!!
                val sub = CommJson.decodeFromString(EventsWsClientEvent.serializer(), frameText) as SubscribeEvents
                assertEquals(EventType.TURN_START, sub.eventType)
                assertTrue(frameText.contains("eventType"))
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun close1008_mapsToAccessRevoked() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/events") {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "operator token required"))
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val ws = EventsWsClient(client, "ws://127.0.0.1:$port", token = "bad")
                val events = withTimeout(10_000) { ws.events(EventFilter()).toList() }

                // Fail-closed: the tail ends in AccessRevoked, NOT a generic Disconnected.
                assertTrue(events.first() is EventLiveEvent.Connected)
                assertTrue(events.last() is EventLiveEvent.AccessRevoked, "1008 close → AccessRevoked")
                assertTrue(events.none { it is EventLiveEvent.Disconnected })
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
