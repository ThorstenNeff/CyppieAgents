package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
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
 * Real `/ws/token-usage` source. The socket auto-reconnects with backoff ([reconnecting]); because the feed is
 * latest-wins/idempotent, a reconnect just re-streams the snapshot, which [TokenUsageViewModel] upserts by
 * `agentId` (never appends) — no duplicates, no loss. Bound to the ACTIVE project's tracker at connect (a project
 * switch reopens the socket, exactly like lifecycle). No REST snapshot endpoint: the WS `onStart` IS the snapshot.
 */
class TokenUsageLiveSource(
    private val client: HttpClient,
    private val wsBaseUrl: String,
    private val token: String,
) : TokenUsageSource {

    override fun events(): Flow<AgentTokenUsageEvent> = channelFlow {
        // channelFlow (not flow): the webSocket body runs on the engine dispatcher, so emitting here is a
        // cross-context send — exactly what channelFlow allows (the flow{} ISE behind the CYP-115 Darwin churn).
        try {
            client.webSocket(urlString = tokenUsageUrl()) {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // The REAL :core event (AgentTokenUsageEvent.serializer()) — no hand-parse, no drift.
                        val event = CommJson.decodeFromString(AgentTokenUsageEvent.serializer(), frame.readText())
                        this@channelFlow.send(event)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // CYP-125 parity: log a real socket failure (a normal close doesn't throw → no per-disconnect noise);
            // `.reconnecting()` still re-subscribes on completion/failure.
            logWsError("token-usage", e)
        }
    }.reconnecting()

    private fun tokenUsageUrl(): String {
        val sep = if (wsBaseUrl.endsWith("/")) "" else "/"
        return "$wsBaseUrl${sep}ws/token-usage?token=$token"
    }
}
