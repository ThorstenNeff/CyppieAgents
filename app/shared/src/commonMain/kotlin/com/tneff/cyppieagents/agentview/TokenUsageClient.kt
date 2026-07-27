package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * CYP-316 — the client source for the per-agent **context-token** feed. Distinct from the lifecycle source (its own
 * `/ws/token-usage` socket, higher per-turn frequency — the same isolation the server chose). Streams the REAL
 * `:core` [AgentTokenUsageEvent] (decoded through the shared [CommJson] — NO hand-parse), snapshot-then-deltas in
 * ONE stream. Participant-gated (read-tier, like `/ws/lifecycle`): a content-free token COUNT only.
 */
interface TokenUsageSource {
    /** Live per-agent context-token events (snapshot on connect, then deltas). Latest-wins per `agentId`. */
    fun events(): Flow<AgentTokenUsageEvent>
}

/**
 * Real `/ws/token-usage` source. The socket auto-reconnects with backoff on a transient drop; because the feed is
 * latest-wins/idempotent, a reconnect just re-streams the snapshot, which [TokenUsageViewModel] upserts by
 * `agentId` (never appends) — no duplicates, no loss. Bound to the ACTIVE project's tracker at connect (a project
 * switch reopens the socket, exactly like lifecycle). No REST snapshot endpoint: the WS `onStart` IS the snapshot.
 *
 * CYP-819: a **1008 (VIOLATED_POLICY) auth-revoke is terminal** — [statusFeed]/[terminalOnRevoke] end the stream
 * (no dead-token reconnect hammer, the CYP-289 loop class) and fire [onRevoked] so the workspace surfaces the revoke
 * instead of freezing the last count as if it were still current (the web-ts twin was CYP-815).
 */
class TokenUsageLiveSource(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val token: String,
    private val onRevoked: () -> Unit = {},
) : TokenUsageSource {

    override fun events(): Flow<AgentTokenUsageEvent> =
        client.statusFeed(tokenUsageUrl(), "token-usage") {
            // The REAL :core event (AgentTokenUsageEvent.serializer()) — no hand-parse, no drift.
            CommJson.decodeFromString(AgentTokenUsageEvent.serializer(), it)
        }.terminalOnRevoke(onRevoked)

    private fun tokenUsageUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/token-usage?token=$token"
    }
}
