package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import kotlinx.coroutines.flow.Flow

/**
 * CYP-354 (BE-1, client mirror) — the read-only client source for the per-agent **terminal-control mode** feed.
 * Content-free by construction: state / holder id / since-time only, NEVER keystrokes or terminal output.
 * Streams the REAL `:core` [AgentTerminalControlEvent]; latest-wins per `agentId`.
 *
 * **Read-only mirror:** this is the display half — the client *mirrors* the backend's mode, it never infers or
 * drives it. The take-over / hand-back actions that move the state machine are BE-2/CYP-355 (not wired here).
 *
 * CYP-846: the live implementation is now a projection of the ONE muxed `/ws/status` socket
 * (`StatusMuxClient.terminal`) — the old standalone `/ws/terminal-state` `TerminalControlLiveSource` is REMOVED.
 * The interface is unchanged, so [TerminalControlStateViewModel] is untouched.
 */
interface TerminalControlSource {
    /** Live per-agent control-state events (snapshot on connect, then deltas). Latest-wins per `agentId`. */
    fun events(): Flow<AgentTerminalControlEvent>
}
