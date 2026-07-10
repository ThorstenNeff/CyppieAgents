package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
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
 * CYP-354 (BE-1, client mirror) — the read-only client source for the per-agent **terminal-control mode** feed.
 * Its own dedicated `/ws/terminal-state` socket (the same per-feed isolation the server chose for
 * lifecycle/busy/token-usage). Streams the REAL `:core` [AgentTerminalControlEvent] (decoded through the shared
 * [CommJson] — NO hand-parse), snapshot-then-deltas in ONE stream. Content-free by construction: state / holder
 * id / since-time only, NEVER keystrokes or terminal output.
 *
 * **Read-only mirror:** this is the display half — the client *mirrors* the backend's mode, it never infers or
 * drives it. The take-over / hand-back actions that move the state machine are BE-2/CYP-355 (not wired here).
 */
interface TerminalControlSource {
    /** Live per-agent control-state events (snapshot on connect, then deltas). Latest-wins per `agentId`. */
    fun events(): Flow<AgentTerminalControlEvent>
}

/**
 * Real `/ws/terminal-state` source. The socket auto-reconnects with backoff ([reconnecting]); because the feed is
 * latest-wins/idempotent, a reconnect just re-streams the snapshot, which [TerminalControlStateViewModel] upserts
 * by `agentId` (never appends) — so a reconnect while INTERACTIVE re-delivers INTERACTIVE and the marker neither
 * hangs nor drops. The WS `onStart` IS the snapshot (no REST snapshot endpoint). Mirrors [BusyStateLiveSource].
 */
class TerminalControlLiveSource(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val token: String,
) : TerminalControlSource {

    override fun events(): Flow<AgentTerminalControlEvent> = channelFlow {
        // channelFlow (not flow): the webSocket body runs on the engine dispatcher, so emitting here is a
        // cross-context send — exactly what channelFlow allows (the flow{} ISE behind the CYP-115 Darwin churn).
        try {
            client.webSocket(urlString = terminalStateUrl()) {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // The REAL :core event (AgentTerminalControlEvent.serializer()) — no hand-parse, no drift.
                        val event = CommJson.decodeFromString(AgentTerminalControlEvent.serializer(), frame.readText())
                        this@channelFlow.send(event)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-125 parity: log a real socket failure (a normal close doesn't throw → no per-disconnect noise);
            // `.reconnecting()` still re-subscribes on completion/failure.
            logWsError("terminal-state", e)
        }
    }.reconnecting()

    private fun terminalStateUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/terminal-state?token=$token"
    }
}
