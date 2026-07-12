package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.model.CaughtUp
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
 * `/ws/events` — Event-Log live-tail (PRD §6, ST6/CYP-40). **CYP-188 B: MEMBER-tier, fail-closed** (was
 * operator-only — now matches `GET /api/events` `authenticatedApi(MEMBER)`, fixing the REST/WS tier mismatch).
 *
 * **Admit:** an agent / operator **token**, OR a **verified human** MEMBER/OPERATOR session (browser cookie or
 * `X-Session-Token`). **Reject:** no/invalid credential → WS close **1008 VIOLATED_POLICY "unauthorized"**. Same
 * post-upgrade close convention as `/ws/agent` and `/ws/comm`; cross-origin browser upgrades are additionally
 * refused pre-handshake by CORS (HTTP 403, CYP-30).
 *
 * The Event-Log is team-wide + secret-free, so every reader sees the active project's events — no per-agent ACL
 * (unlike `/ws/comm`). The cross-project `?projectId` override (CYP-94) stays **operator-only** (a non-operator's
 * authorized set is empty → forced-active). [SubscribeEvents] otherwise only narrows the caller's own view.
 */
// [activeProjectId] defaults to unscoped for legacy single-store WS tests; production (installPlatform)
// always passes the registry active-pointer resolver (CYP-102). [authorizedProjects] (S17 / CYP-94) is
// the operator's own project set — bounds the optional cross-project read override; defaulted empty so
// legacy installs never honor an override.
fun Route.eventSocket(
    sink: EventSink,
    registry: TokenRegistry,
    activeProjectId: () -> String? = { null },
    authorizedProjects: () -> Set<String> = { emptySet() },
    deps: com.tneff.cyppieagents.auth.AuthDeps = com.tneff.cyppieagents.auth.AuthDeps(registry),
) {
    webSocket("/ws/events") {
        // CYP-188 B: MEMBER-tier read (was operator-only) — matches GET /api/events (authenticatedApi MEMBER),
        // fixing the REST(MEMBER)/WS(operator-only) tier mismatch: an agent/operator token OR a verified human
        // MEMBER/OPERATOR session may tail the (team-wide, secret-free) event-log live. No-cred → close.
        val reader = call.wsReaderOrNull(deps, registry)
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        // The cross-project `?projectId` override (CYP-94) stays OPERATOR-only: a non-operator's authorized set
        // is empty, so any override falls back to forced-active (same as REST). Operator = op-token OR human-OP.
        val isOperator = reader == HubState.OPERATOR_ID

        // S13 / CYP-102: resolve the active project server-side at connect time. The SINGLE scope
        // chokepoint is the in-process [filter] (we subscribe to ALL and enforce here — no second,
        // redundant guard on `subscribe` that could mask a regression / drift). The base pins the active
        // project when the client hasn't narrowed; the re-pin below keeps the scope when the client DOES
        // narrow. S17 / CYP-94: an operator's `SubscribeEvents.projectId` override is resolved through the
        // SAME `resolveEventScope` as REST — bounded to the operator's authorized set, fail-closed; with
        // no override it stays forced-active (CYP-102 unchanged). Non-operators get an empty authorized set.
        val active = activeProjectId()
        // Operator-only override (CYP-94): a non-operator (agent token / human MEMBER) gets an EMPTY authorized
        // set → every `?projectId` override falls back to forced-active. Only an operator can cross-project.
        val authorized = if (isOperator) authorizedProjects() else emptySet()
        var filter = EventFilter(projectId = resolveEventScope(null, active, authorized)) // base = forced-active

        suspend fun emit(event: EventsWsServerEvent) =
            send(Frame.Text(CommJson.encodeToString(EventsWsServerEvent.serializer(), event)))

        // CYP-499: announce CaughtUp once the live subscription is established so the client flips
        // "Verlauf lädt…" → "Live". The live-tail carries only the LIVE stream (`subscribe` is a hot, no-replay
        // SharedFlow; history is the Browse window / GET /api/events), so "the stream is live" holds as soon as we
        // subscribe. The marker is already in the EventsWsServerEvent contract and the web-ts client already
        // consumes it (App.tsx `caughtUp`) — the server simply never sent it, so the live indicator never
        // activated. `emit` is the single sender (this one coroutine), so CaughtUp can't interleave a frame; the
        // client folds the marker independently (order-agnostic) and is idempotent on reconnect (fresh socket →
        // fresh CaughtUp).
        val pump = launch {
            emit(CaughtUp)
            sink.subscribe(EventFilter.ALL).collect { event ->
                if (filter.matches(event)) emit(EventPushed(event))
            }
        }
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val msg = runCatching { CommJson.decodeFromString<EventsWsClientEvent>(frame.readText()) }.getOrNull()
                    // Re-pin scope through the resolver: a client narrow keeps forced-active unless it
                    // carries an AUTHORIZED override; an unauthorized/garbage projectId falls back to active.
                    if (msg is SubscribeEvents) {
                        filter = msg.toEventFilter().copy(projectId = resolveEventScope(msg.projectId, active, authorized))
                    }
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
