package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import kotlinx.coroutines.flow.Flow

/**
 * CYP-316 — the client source for the per-agent **context-token** feed. A content-free token COUNT only.
 * Streams the REAL `:core` [AgentTokenUsageEvent]; latest-wins per `agentId`.
 *
 * CYP-846: the live implementation is now a projection of the ONE muxed `/ws/status` socket
 * (`StatusMuxClient.tokenUsage`) — the old standalone `/ws/token-usage` `TokenUsageLiveSource` is REMOVED. The
 * interface is unchanged, so [TokenUsageViewModel] is untouched.
 */
interface TokenUsageSource {
    /** Live per-agent context-token events (snapshot on connect, then deltas). Latest-wins per `agentId`. */
    fun events(): Flow<AgentTokenUsageEvent>
}
