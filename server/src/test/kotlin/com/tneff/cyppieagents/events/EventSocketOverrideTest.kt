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
 * S17 / CYP-94 — `/ws/events` operator-only cross-project read override via `SubscribeEvents.projectId`,
 * resolved through the SAME `resolveEventScope` as REST. Authorized override is honored; an unauthorized
 * id fails closed to the active project (never widens) — over a real handshake.
 */
class EventSocketOverrideTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")
    private val authorized = setOf("alpha", "beta")

    private fun ApplicationTestBuilder.serve(sink: InMemoryEventSink) {
        install(WebSockets)
        routing { eventSocket(sink, registry, activeProjectId = { "alpha" }, authorizedProjects = { authorized }) }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    /** Append [a] then [b] events until [target] of [wantTeam] are seen; return all seen projectIds. */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.collect(
        sink: InMemoryEventSink, a: String, b: String, wantTeam: String, target: Int = 2,
    ): List<String> = withTimeout(5_000) {
        val seen = mutableListOf<String>()
        while (seen.count { it == wantTeam } < target) {
            sink.append(draft(agent = "x", team = a))
            sink.append(draft(agent = "y", team = b))
            val f = withTimeoutOrNull(50) { incoming.receive() }
            if (f is Frame.Text) {
                (CommJson.decodeFromString<EventsWsServerEvent>(f.readText()) as? EventPushed)?.let { seen += it.event.projectId }
            }
        }
        seen
    }

    @Test fun authorizedOverride_streamsThatProject() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource()); serve(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            send(Frame.Text(CommJson.encodeToString<EventsWsClientEvent>(SubscribeEvents(projectId = "beta"))))
            delay(200)
            val seen = collect(sink, a = "alpha", b = "beta", wantTeam = "beta")
            assertTrue(seen.all { it == "beta" }, "authorized override → only beta streams (alpha dropped): $seen")
        }
    }

    @Test fun unauthorizedOverride_failsClosedToActive() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource()); serve(sink)
        wsClient().webSocket("/ws/events?token=tok-op") {
            // gamma is NOT in the authorized set → must fall back to active (alpha), never stream gamma
            send(Frame.Text(CommJson.encodeToString<EventsWsClientEvent>(SubscribeEvents(projectId = "gamma"))))
            delay(200)
            val seen = collect(sink, a = "alpha", b = "gamma", wantTeam = "alpha")
            assertTrue(seen.all { it == "alpha" }, "unauthorized override fails closed to active; no gamma leak: $seen")
        }
    }
}
