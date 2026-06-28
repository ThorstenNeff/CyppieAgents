package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.scanner.Signal
import com.tneff.cyppieagents.scanner.SignalSink
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The **single write authority toward an agent** (07 §3). Every effect on an agent — the nudge — and
 * every escalation to the PO flows through here, and every action it takes is mirrored as an action
 * Event on the bus so the whole Decide→Act step is visible in the Live-Tail (06).
 *
 * Nothing else in the Sense/Decide stack holds an agent handle: detectors get only an `Event`, the
 * Warden gets only this interface, policies get only this interface. So a write toward an agent that
 * bypasses the Actuator is impossible by construction, not by review discipline.
 */
interface Actuator {
    /** Wake a stuck agent: a `"keep going"` turn on the Mediator stdin (no new channel). */
    suspend fun nudge(agentId: String, text: String)

    /** Hand a persistent problem to the PO (after a policy's attempts are exhausted). */
    suspend fun escalateToPO(agentId: String, reason: String)
}

/**
 * The production [Actuator]: it nudges by **injecting a user-turn on the agent's existing session**
 * (`ConnectorSessions.session(id).sendTurn`) — the exact same Mediator-stdin path a PO task takes
 * (05 §2), through the single-flight turn-queue, so a nudge can't race a running turn. It then emits
 * the action Event via the shared [SignalSink] write contract (stamped/ordered by the one authority).
 *
 * Metadata-only: the nudge **text is not recorded** (it is platform-authored, but the rule is uniform
 * — no message bodies on the bus). `nudge.sent` carries only that a nudge happened; `po.escalated`
 * carries the short platform-authored `reason`, never agent content.
 */
class MediatorActuator(
    private val sessions: ConnectorSessions,
    private val signals: SignalSink,
    private val teamId: String,
) : Actuator {
    private val log = LoggerFactory.getLogger("warden.actuator")

    override suspend fun nudge(agentId: String, text: String) {
        val session = sessions.session(agentId)
        if (session == null) {
            // Fail-closed honesty: no live session → nothing was sent → no `nudge.sent` is emitted.
            log.warn("nudge skipped: no live session for agent '{}'", agentId)
            return
        }
        session.sendTurn(UserTurn(text)) // THE ONLY write toward an agent in the whole supervision stack
        signals.emit(Signal(type = NUDGE_SENT, agentId = agentId, teamId = teamId))
    }

    override suspend fun escalateToPO(agentId: String, reason: String) {
        signals.emit(
            Signal(
                type = PO_ESCALATED,
                agentId = agentId,
                teamId = teamId,
                evidence = buildJsonObject { put("reason", reason) },
            ),
        )
    }

    private companion object {
        const val NUDGE_SENT = "nudge.sent"
        const val PO_ESCALATED = "po.escalated"
    }
}
