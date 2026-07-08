package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.net.logWsError
import com.tneff.cyppieagents.net.reconnecting
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * CYP-324 — the client source for the per-agent **busy/idle** feed. Its own dedicated `/ws/busy-state` socket
 * (the same per-feed isolation the server chose for lifecycle/token-usage). Streams the REAL `:core`
 * [AgentBusyStateEvent] (decoded through the shared [CommJson] — NO hand-parse), snapshot-then-deltas in ONE
 * stream. Participant-gated (read-tier, like `/ws/lifecycle`): a content-free busy FLAG only.
 */
interface BusyStateSource {
    /** Live per-agent busy events (snapshot on connect, then deltas). Latest-wins per `agentId`. */
    fun events(): Flow<AgentBusyStateEvent>
}

/**
 * Real `/ws/busy-state` source. The socket auto-reconnects with backoff ([reconnecting]); because the feed is
 * latest-wins/idempotent, a reconnect just re-streams the snapshot, which [BusyStateViewModel] upserts by
 * `agentId` (never appends). A reconnect MID-TURN correctly re-delivers `busy = true` (the server derives busy
 * from real session state), so the `*` never hangs and never drops spuriously. Bound to the ACTIVE project's
 * tracker at connect (a project switch reopens the socket, exactly like lifecycle). No REST snapshot endpoint:
 * the WS `onStart` IS the snapshot.
 */
class BusyStateLiveSource(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val token: String,
) : BusyStateSource {

    override fun events(): Flow<AgentBusyStateEvent> = channelFlow {
        // channelFlow (not flow): the webSocket body runs on the engine dispatcher, so emitting here is a
        // cross-context send — exactly what channelFlow allows (the flow{} ISE behind the CYP-115 Darwin churn).
        try {
            client.webSocket(urlString = busyStateUrl()) {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // The REAL :core event (AgentBusyStateEvent.serializer()) — no hand-parse, no drift.
                        val event = CommJson.decodeFromString(AgentBusyStateEvent.serializer(), frame.readText())
                        this@channelFlow.send(event)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-125 parity: log a real socket failure (a normal close doesn't throw → no per-disconnect noise);
            // `.reconnecting()` still re-subscribes on completion/failure.
            logWsError("busy-state", e)
        }
    }.reconnecting()

    private fun busyStateUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/busy-state?token=$token"
    }
}
