package com.tneff.cyppieagents.agentview

import kotlinx.coroutines.flow.Flow

/**
 * The renderer's view of one agent's conversation.
 *
 * Deliberately Hub-mediated, not a direct process pipe (05 §2): the client only ever speaks
 * Hub REST/WS. [sendMessage] means "post a user-message to the agent's channel"; the mediator
 * in `:server` translates that channel message into a user-message on the agent's stdin. The
 * client never touches stdin and never assumes a direct connection to the process.
 *
 * Correspondingly, events arriving on [events] are expected to be *already masked* by the
 * mediator (D3 secret hygiene) — the renderer never receives raw tool args/results.
 */
interface AgentSession {
    /** Hub → renderer. Masked, UI-shaped events for this agent. */
    val events: Flow<AgentEvent>

    /** Renderer → Hub. Posts a human turn as a message to the agent's channel. */
    fun sendMessage(text: String)
}
