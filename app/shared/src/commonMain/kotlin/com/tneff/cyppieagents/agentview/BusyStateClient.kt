package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

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
 * Real `/ws/busy-state` source. The socket auto-reconnects with backoff on a transient drop; because the feed is
 * latest-wins/idempotent, a reconnect just re-streams the snapshot, which [BusyStateViewModel] upserts by
 * `agentId` (never appends). A reconnect MID-TURN correctly re-delivers `busy = true` (the server derives busy
 * from real session state), so the `*` never hangs and never drops spuriously. Bound to the ACTIVE project's
 * tracker at connect (a project switch reopens the socket, exactly like lifecycle). No REST snapshot endpoint:
 * the WS `onStart` IS the snapshot.
 *
 * CYP-819: a **1008 (VIOLATED_POLICY) auth-revoke is terminal** — [statusFeed]/[terminalOnRevoke] end the stream
 * (no dead-token reconnect hammer, the CYP-289 loop class) and fire [onRevoked] so the workspace surfaces the revoke
 * instead of freezing the last busy flag as if it were still current (the web-ts twin was CYP-815).
 */
class BusyStateLiveSource(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val token: String,
    private val onRevoked: () -> Unit = {},
) : BusyStateSource {

    override fun events(): Flow<AgentBusyStateEvent> =
        client.statusFeed(busyStateUrl(), "busy-state") {
            // The REAL :core event (AgentBusyStateEvent.serializer()) — no hand-parse, no drift.
            CommJson.decodeFromString(AgentBusyStateEvent.serializer(), it)
        }.terminalOnRevoke(onRevoked)

    private fun busyStateUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/busy-state?token=$token"
    }
}
