package com.tneff.cyppieagents.mediation

import java.util.concurrent.ConcurrentHashMap

/**
 * The authoritative `session → agentId` mapping (Reviewer Gate #1). The mediator binds a CLI
 * session id to the agent it spawned it for; routing of that session's output is derived ONLY
 * from this mapping, never from message text.
 */
class SessionRegistry {
    private val sessionToAgent = ConcurrentHashMap<String, String>()

    fun bind(sessionId: String, agentId: String) {
        sessionToAgent[sessionId] = agentId
    }

    fun agentFor(sessionId: String): String? = sessionToAgent[sessionId]

    fun unbind(sessionId: String) {
        sessionToAgent.remove(sessionId)
    }
}
