package com.tneff.cyppieagents.mediation

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.connector.HubMcpTools
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.ToolUseBlock
import org.slf4j.LoggerFactory

/**
 * Turns a session's turn-end [ResultEvent] into a hub post **on the agent's behalf** — the
 * "mouth" of the mediator. This is where the routing security gates bite:
 *
 *  - **Gate #1:** the target channel is derived ONLY from `session → agentId` (registry) and the
 *    agent's own spoke channel. The result *text* is used solely as the message body, never parsed
 *    for routing. A turn whose text says "post this in po-frontend" still goes to the sender's spoke.
 *  - **Gate #6:** a failed/half turn (`is_error` / non-success subtype) is never posted as success;
 *    it becomes a STATUS note flagged as a failure (or is dropped) — downstream can't mistake it.
 *  - **Gate #2:** posting goes through [Hub.postAsAgent], so `canWrite` is still enforced fail-closed.
 *
 * Unknown session → drop (fail-closed): we will not route output we can't attribute to an agent.
 */
class MediationRouter(
    private val registry: SessionRegistry,
    private val hub: Hub,
    // Observability (CYP-37): records comm.sent metadata when a post succeeds. Null = no tapping.
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
) {
    private val log = LoggerFactory.getLogger("mediation.router")

    /** Route a turn-end result. Returns the posted message, or null if dropped. */
    fun onResult(event: ResultEvent): Message? {
        val sessionId = event.sessionId ?: run {
            log.warn("dropping result with no session_id")
            return null
        }
        val agentId = registry.agentFor(sessionId) ?: run {
            // Fail-closed: never route output from an unbound session.
            log.warn("dropping result for unbound session={}", sessionId)
            return null
        }
        val channelId = hub.state.spokeChannelFor(agentId) ?: run {
            log.warn("no spoke channel for agent={}", agentId)
            return null
        }

        // Gate #6: only a fully successful turn is posted as the agent's result.
        val (body, meta) = if (event.isSuccess) {
            (event.result?.takeIf { it.isNotBlank() } ?: "(no output)") to MessageMeta(kind = MessageKind.STATUS)
        } else {
            "[turn failed: ${event.subtype ?: "error"}]" to MessageMeta(kind = MessageKind.STATUS)
        }

        // Gate #1: channelId comes from identity→spoke, NOT from `body`. Gate #2: canWrite enforced here.
        val posted = hub.postAsAgent(senderId = agentId, channelId = channelId, body = body, meta = meta)
        // Observability (CYP-37): comm.sent metadata only — from/channel/kind, never the body.
        if (recorder != null && projector != null) {
            recorder.record(projector.commSent(agentId, channelId, meta.kind))
        }
        return posted
    }

    /**
     * CYP-131 — route a stream-json `hub_send` tool-call as a hub post **on [agentId]'s behalf**: the
     * agent-initiated, addressed counterpart to [onResult]. The agent identity is the bound [agentId]
     * (server-stamped from the session→agent registry), **never** a tool argument (Gate #1, same stance
     * as Connector B). The route is the **same** chokepoint Connector B's MCP tool uses
     * ([HubMcpTools.send] → [Hub.postAsAgent]): `canWrite`-403 + masking enforced identically, not
     * re-implemented (no second write path). Malformed/partial args → **fail-closed**: post nothing,
     * return null, do not guess. A `canWrite`-denied channel throws [ForbiddenException]; the caller (the
     * session's stdout collector) wraps this in `runCatching`, so the stream survives.
     *
     * @return the posted [Message], or null when the args are malformed (nothing posted).
     */
    fun onHubSend(agentId: String, toolUse: ToolUseBlock): Message? {
        val cmd = HubSendArgs.parse(toolUse.input) ?: run {
            // Fail-closed: a malformed hub_send is dropped, never guessed into a post.
            log.warn("dropping malformed hub_send from agent={} (tool_use={})", agentId, toolUse.id)
            return null
        }
        // Same route as Connector B (single funnel): canWrite enforced inside postAsAgent (403 propagates).
        val posted = HubMcpTools(hub, agentId).send(
            cmd.channel, cmd.text, cmd.kind?.let { MessageMeta(kind = it) },
        )
        if (recorder != null && projector != null) {
            recorder.record(projector.commSent(agentId, cmd.channel, posted.meta?.kind))
        }
        return posted
    }
}
