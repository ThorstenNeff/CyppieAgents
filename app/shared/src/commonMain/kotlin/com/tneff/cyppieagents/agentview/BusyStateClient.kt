package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentBusyStateEvent
import kotlinx.coroutines.flow.Flow

/**
 * CYP-324 — the client source for the per-agent **busy/idle** feed. A content-free busy FLAG only.
 * Streams the REAL `:core` [AgentBusyStateEvent]; latest-wins per `agentId`.
 *
 * CYP-846: the live implementation is now a projection of the ONE muxed `/ws/status` socket
 * (`StatusMuxClient.busy`) — the old standalone `/ws/busy-state` `BusyStateLiveSource` is REMOVED. The interface is
 * unchanged, so [BusyStateViewModel] is untouched.
 */
interface BusyStateSource {
    /** Live per-agent busy events (snapshot on connect, then deltas). Latest-wins per `agentId`. */
    fun events(): Flow<AgentBusyStateEvent>
}
