package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CaughtUp
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.SubscribeEvents
import com.tneff.cyppieagents.net.isAccessRevoked
import com.tneff.cyppieagents.net.logWsError
import com.tneff.cyppieagents.net.readCloseCode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Live `/ws/events` adapter (CYP-40), the drop-in for [StubEventsSource]. Decodes each `:core`
 * [EventsWsServerEvent] frame via [CommJson] and maps it onto the UI [EventLiveEvent] seam — so the
 * reducer/VM/panel are unchanged. Mirrors `CommWsClient`.
 *
 * Contract (CYP-40): `GET {hubWsBaseUrl}/ws/events?token=<t>`; the client first sends a
 * [SubscribeEvents] frame (the event-type axis is `eventType`, not `type`, to avoid the
 * `classDiscriminator = "type"` collision); the server pushes [EventPushed] (+ optional [CaughtUp]).
 * Operator-only, fail-closed: a missing/invalid operator token closes the socket with **1008**
 * (`VIOLATED_POLICY`) → emitted as [EventLiveEvent.AccessRevoked] so the tail renders an honest reject
 * (never "live"). The token also goes in the `?token=` query for the browser (WS headers aren't settable).
 */
class EventsWsClient(
    private val client: HttpClient,
    private val hubWsBaseUrl: String,
    private val token: String,
) : EventLiveSource {

    override fun events(filter: EventFilter): Flow<EventLiveEvent> = channelFlow {
        var closeCode: Short? = null
        try {
            client.webSocket(
                urlString = eventsUrl(),
                request = { header(HttpHeaders.Authorization, "Bearer $token") },
            ) {
                // channelFlow (not flow): the webSocket body runs on the engine dispatcher (Dispatchers.IO on
                // Native), so emitting from here is a cross-context send — illegal in flow{} (the ISE behind
                // the CYP-115 Darwin churn) but what channelFlow allows. Element emissions go to
                // this@channelFlow; the subscribe + `incoming` frames stay on the WebSocketSession.
                this@channelFlow.send(EventLiveEvent.Connected)
                try {
                    send(Frame.Text(CommJson.encodeToString(EventsWsClientEvent.serializer(), subscribe(filter))))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            when (val event = CommJson.decodeFromString(EventsWsServerEvent.serializer(), frame.readText())) {
                                is EventPushed -> this@channelFlow.send(EventLiveEvent.Received(event.event))
                                is CaughtUp -> Unit // backfill boundary marker — nothing to render
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Ktor tears a closing socket down by cancelling the frame channel. If the socket
                    // already recorded a close reason, this is that teardown (e.g. a 1008 reject) — not a
                    // real collector cancellation — so swallow it and report the close below. Otherwise
                    // it's a genuine cancel and must propagate.
                    if (!closeReason.isCompleted) throw e
                }
                // CYP-293: read the close code via the shared helper (NonCancellable, so a 1008 is reported even
                // while the socket tears down) — one place for the close-code contract across all /ws/ clients.
                closeCode = readCloseCode()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-115: log a real failure (incl. cross-origin 403 pre-handshake) instead of masking it as a
            // silent Disconnected (a normal close does not throw). The cross-context ISE was swallowed here before.
            logWsError("events", e)
        }
        // Always end honestly: a rejected operator token (1008) is a distinct, fail-closed state.
        this@channelFlow.send(
            if (isAccessRevoked(closeCode)) EventLiveEvent.AccessRevoked
            else EventLiveEvent.Disconnected,
        )
    }

    private fun subscribe(filter: EventFilter) = SubscribeEvents(
        agentId = filter.agentId,
        eventType = filter.type, // `eventType` on the wire — not `type` (discriminator collision)
        severity = filter.severity,
        correlationId = filter.correlationId,
        sessionId = filter.sessionId,
        since = filter.since,
        until = filter.until,
        // CYP-94 WS real-swap: the cross-project lens rides the :core `SubscribeEvents.projectId` (server
        // honors it operator-only; null/no-override = forced-active, CYP-102). Matches the locked wire form.
        projectId = filter.projectId,
    )

    private fun eventsUrl(): String {
        val sep = if (hubWsBaseUrl.endsWith("/")) "" else "/"
        return "$hubWsBaseUrl${sep}ws/events?token=$token"
    }
}
