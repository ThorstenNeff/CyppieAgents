package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.SubscribeEvents
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.eventSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ST6 (CYP-40) `/ws/events` over a REAL handshake (not `client.get`): operator receives live pushes,
 * filter narrows, and the fail-closed reject signal (close 1008) fires for no-token / agent-token.
 */
class EventSocketTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")

    private fun ApplicationTestBuilder.serve(sink: InMemoryEventSink) {
        install(WebSockets)
        routing { eventSocket(sink, registry) }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    @Test
    fun operatorReceivesLivePushes() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            // Append until the live subscription delivers one (robust against subscribe latency).
            val pushed = withTimeout(5_000) {
                var hit: EventPushed? = null
                while (hit == null) {
                    sink.append(draft(agent = "backend", type = EventType.TURN_START))
                    val f = withTimeoutOrNull(50) { incoming.receive() }
                    if (f is Frame.Text) {
                        (CommJson.decodeFromString<EventsWsServerEvent>(f.readText()) as? EventPushed)?.let { hit = it }
                    }
                }
                hit
            }
            assertEquals("backend", pushed.event.agentId)
            close()
        }
    }

    @Test
    fun subscribeNarrowsByEventType() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            send(Frame.Text(CommJson.encodeToString<EventsWsClientEvent>(SubscribeEvents(eventType = EventType.TOOL_CALL))))
            delay(200) // let the server apply the filter before we append

            val received = withTimeout(5_000) {
                val seen = mutableListOf<EventType>()
                while (seen.size < 3) {
                    sink.append(draft(agent = "backend", type = EventType.TURN_START)) // filtered out
                    sink.append(draft(agent = "backend", type = EventType.TOOL_CALL)) // passes
                    val f = withTimeoutOrNull(50) { incoming.receive() }
                    if (f is Frame.Text) {
                        seen += (CommJson.decodeFromString<EventsWsServerEvent>(f.readText()) as EventPushed).event.type
                    }
                }
                seen
            }
            assertTrue(received.all { it == EventType.TOOL_CALL }, "filter must drop non-matching types: $received")
            close()
        }
    }

    @Test
    fun noToken_failsClosed_close1008() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events") {
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun agentToken_rejected_close1008() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-be") {
            val reason = withTimeout(3_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "agent token is not operator → fail-closed")
        }
    }
}
