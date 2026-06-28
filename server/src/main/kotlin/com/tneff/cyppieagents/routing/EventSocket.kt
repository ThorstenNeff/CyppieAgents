package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.SubscribeEvents
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.launch

/**
 * `/ws/events` — Event-Log live-tail (PRD §6, ST6/CYP-40). **Operator-only, fail-closed.**
 *
 * **Reject signal (owner-defined, CYP-40):**
 *  - no token → WS close **1008 VIOLATED_POLICY "unauthorized"**;
 *  - a valid **non-operator** (agent) token → WS close **1008 VIOLATED_POLICY "operator token required"**.
 *
 * Same post-upgrade close convention as `/ws/agent` and `/ws/comm`. Cross-origin browser upgrades are
 * additionally refused **pre-handshake** by the CORS plugin (HTTP 403, CYP-30). The UI renders
 * fail-closed on the 1008 close; the Tester asserts the close code + zero events via a real handshake.
 *
 * The Event-Log is team-wide, so the operator sees everything — there is no per-agent ACL here (unlike
 * `/ws/comm`); [SubscribeEvents] only narrows the operator's own view.
 */
// [activeProjectId] defaults to unscoped for legacy single-store WS tests; production (installPlatform)
// always passes the registry active-pointer resolver (CYP-102).
fun Route.eventSocket(sink: EventSink, registry: TokenRegistry, activeProjectId: () -> String? = { null }) {
    webSocket("/ws/events") {
        val token = call.bearerToken() ?: call.request.queryParameters["token"]
        if (token == null) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        }
        if (!registry.isOperator(token)) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "operator token required"))
        }

        // S13 / CYP-102: resolve the active project server-side at connect time. The SINGLE scope
        // chokepoint is the in-process [filter] (we subscribe to ALL and enforce here — no second,
        // redundant guard on `subscribe` that could mask a regression / drift). The base pins the active
        // project when the client hasn't narrowed; the re-pin below keeps the project scope when the
        // client DOES narrow (a SubscribeEvents may adjust other axes but NEVER widen past the project).
        // A switch re-scopes on reconnect (the UI re-subscribes on project switch — per-stream live
        // re-scope is S17 with hub-instancing).
        val project = activeProjectId()
        var filter = EventFilter(projectId = project) // base scope — the sole guard until a client narrow

        suspend fun emit(event: EventsWsServerEvent) =
            send(Frame.Text(CommJson.encodeToString(EventsWsServerEvent.serializer(), event)))

        val pump = launch {
            sink.subscribe(EventFilter.ALL).collect { event ->
                if (filter.matches(event)) emit(EventPushed(event))
            }
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val msg = runCatching { CommJson.decodeFromString<EventsWsClientEvent>(frame.readText()) }.getOrNull()
                    // Re-pin the project scope so a client narrow can't widen past the active project.
                    if (msg is SubscribeEvents) filter = msg.toEventFilter().copy(projectId = project)
                }
            }
        } finally {
            pump.cancel()
        }
    }
}

private fun SubscribeEvents.toEventFilter() = EventFilter(
    agentId = agentId,
    type = eventType,
    severity = severity,
    since = since,
    until = until,
    correlationId = correlationId,
    sessionId = sessionId,
)
