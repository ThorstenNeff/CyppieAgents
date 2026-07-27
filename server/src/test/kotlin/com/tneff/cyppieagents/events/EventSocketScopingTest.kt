package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPushed
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * S13 / CYP-102 — `/ws/events` live-tail scoping by active projectId, over a REAL handshake (like
 * [EventSocketTest]). The Event-Log is a SHARED multi-project store, so the live-tail must restrict to
 * the active project and a client `SubscribeEvents` must not widen past it.
 *
 * **Two INDEPENDENT axes** (the single scope chokepoint is the in-process `filter`; subscribe is ALL):
 *  1. only-active-streams — the base pin `filter = EventFilter(projectId)` is sole guard when the client
 *     has NOT narrowed. Mutation: base → ALL ⇒ THIS axis reddens (the other stays green).
 *  2. no-widen — the `.copy(projectId)` re-pin is sole guard AFTER the client narrows. Mutation: drop
 *     `.copy` ⇒ THIS axis reddens (the other stays green).
 */
class EventSocketScopingTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    /** Active project pinned to alpha; the resolver is the server-side pointer (no client header). */
    private fun ApplicationTestBuilder.serveScoped(sink: InMemoryEventSink) {
        install(WebSockets)
        routing { eventSocket(sink, registry, activeProjectId = { "alpha" }) }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    /** Append beta (must NEVER arrive) + alpha (must arrive) until [target] alpha seen; return all seen projectIds. */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.collectProjectIds(
        sink: InMemoryEventSink,
        target: Int = 2,
    ): List<String> = withTimeout(5_000) {
        val seen = mutableListOf<String>()
        while (seen.count { it == "alpha" } < target) {
            sink.append(draft(agent = "b", team = "beta")) // out-of-scope: must be dropped
            sink.append(draft(agent = "a", team = "alpha")) // in-scope: must stream
            val f = withTimeoutOrNull(50) { incoming.receive() }
            if (f is Frame.Text) {
                (CommJson.decodeFromString<EventsWsServerEvent>(f.readText()) as? EventPushed)
                    ?.let { seen += it.event.projectId }
            }
        }
        seen
    }

    @Test
    fun onlyActiveProjectStreams_noClientNarrow() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serveScoped(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            // No SubscribeEvents → the base pin is the sole guard.
            val seen = collectProjectIds(sink)
            assertTrue(seen.all { it == "alpha" }, "live-tail streams only the active project; no beta leak: $seen")
        }
    }

    @Test
    fun clientCannotWidenPastActiveProject() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serveScoped(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            // A widen attempt: an empty SubscribeEvents clears narrows → only the `.copy` re-pin holds scope.
            send(Frame.Text(CommJson.encodeToString<EventsWsClientEvent>(SubscribeEvents())))
            delay(200) // let the server apply the client filter before we append
            val seen = collectProjectIds(sink)
            assertTrue(seen.all { it == "alpha" }, "a client narrow can't widen past the active project; no beta leak: $seen")
        }
    }
}
