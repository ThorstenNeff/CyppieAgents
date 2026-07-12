package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CaughtUp
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
 * ST6 (CYP-40) `/ws/events` over a REAL handshake (not `client.get`): a reader receives live pushes and the
 * filter narrows. Fail-closed reject signal (close 1008) fires for no-token. CYP-188 B: the socket is now
 * **MEMBER-tier** (matches `GET /api/events`), so an **agent token is ADMITTED** (was operator-only) — the
 * cross-project override stays operator-only (covered by [EventSocketOverrideTest]).
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
    fun emitsCaughtUp_onConnect_soClientFlipsToLive() = testApplication {
        // CYP-499: the server MUST send CaughtUp once the live stream is established — the marker was in the
        // EventsWsServerEvent contract and the web-ts client consumes it (App.tsx `caughtUp` → "Live"), but the
        // server sent it zero times, so the live indicator hung on "Verlauf lädt…". With no append at all, the
        // FIRST frame is the marker. Mutation: remove `emit(CaughtUp)` → this receive times out / is an EventPushed.
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            val first = withTimeout(5_000) {
                CommJson.decodeFromString<EventsWsServerEvent>((incoming.receive() as Frame.Text).readText())
            }
            assertEquals(CaughtUp, first, "the first /ws/events frame must be the CaughtUp live-boundary marker")
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
                        // CYP-499: skip the CaughtUp marker (now the first frame) — only collect EventPushed types.
                        (CommJson.decodeFromString<EventsWsServerEvent>(f.readText()) as? EventPushed)?.let { seen += it.event.type }
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
    fun agentToken_admitted_receivesLivePushes() = testApplication {
        // CYP-188 B: MEMBER-tier — an agent token now tails the (team-wide, secret-free) event-log (was 1008).
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        wsClient().webSocket("/ws/events?token=tok-be") {
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
            assertEquals("backend", pushed.event.agentId, "agent token is admitted at MEMBER tier and receives pushes")
            close()
        }
    }
}
