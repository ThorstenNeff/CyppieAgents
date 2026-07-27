package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

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
 * Real `/ws/terminal-state` source. The socket auto-reconnects with backoff on a transient drop; because the feed is
 * latest-wins/idempotent, a reconnect just re-streams the snapshot, which [TerminalControlStateViewModel] upserts
 * by `agentId` (never appends) — so a reconnect while INTERACTIVE re-delivers INTERACTIVE and the marker neither
 * hangs nor drops. The WS `onStart` IS the snapshot (no REST snapshot endpoint). Mirrors [BusyStateLiveSource].
 *
 * CYP-819: a **1008 (VIOLATED_POLICY) auth-revoke is terminal** — [statusFeed]/[terminalOnRevoke] end the stream
 * (no dead-token reconnect hammer, the CYP-289 loop class) and fire [onRevoked] so the workspace surfaces the revoke
 * instead of freezing the last control state as if it were still current (the web-ts twin was CYP-815).
 */
class TerminalControlLiveSource(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val token: String,
    private val onRevoked: () -> Unit = {},
) : TerminalControlSource {

    override fun events(): Flow<AgentTerminalControlEvent> =
        client.statusFeed(terminalStateUrl(), "terminal-state") {
            // The REAL :core event (AgentTerminalControlEvent.serializer()) — no hand-parse, no drift.
            CommJson.decodeFromString(AgentTerminalControlEvent.serializer(), it)
        }.terminalOnRevoke(onRevoked)

    private fun terminalStateUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/terminal-state?token=$token"
    }
}
