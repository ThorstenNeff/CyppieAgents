package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * Connector B — Claude Code via **MCP** (Doc 10 §5, opt-in, subscription/interactive). Unlike Connector A
 * (stream-json over piped stdio, the Mediator reads stdout + injects stdin), Connector B's agent talks to
 * **our hub through an MCP server we provide** — it pulls tasks via `hub_inbox`/`hub_watch` and answers via
 * `hub_send` ([HubMcpTools]). Coordination is therefore structured MCP tool-calls, not TUI scraping.
 *
 * **Capabilities (Doc 10 §4 col B):** declared honestly LOWER than A — structuredUsage UNAVAILABLE (no
 * token tracking), toolGranularity/reliableResult/rateLimitSignal LIMITED (thinner / less reliable /
 * text-matched), coordination AVAILABLE (the MCP tools ARE the coordination). The Mediator gates on these.
 *
 * **Scope split (CYP-122):** the MCP **tool handlers** ([HubMcpTools]) are built + tested here — they route
 * through the SAME [Hub.postAsAgent]/canWrite/SecretMasker + ACL chokepoints as REST/mediation, so ACL +
 * masking are enforced identically (Tester-C4). The **live interactive Claude-Code attach** through a real
 * MCP transport (host-side subscription OAuth) + the empirical billing classification (Doc 10 §6.1) is the
 * DEFERRED, human-gated track — so [McpSession]'s stream ([events]) and inbound ([sendTurn]) are stubs here.
 */
class McpConnector(
    private val hub: Hub,
) : Connector {

    override val capabilities: Capabilities = MCP_CAPABILITIES

    override fun open(agentId: String): ConnectorSession = McpSession(agentId, hub)

    companion object {
        /** Connector B fidelity (Doc 10 §4 col B). Lower than A, declared honestly so the Mediator degrades. */
        val MCP_CAPABILITIES = Capabilities(
            structuredUsage = CapabilityStatus.UNAVAILABLE, // no per-turn token tracking over MCP
            toolGranularity = CapabilityStatus.LIMITED,     // thinner tool.* events
            reliableResult = CapabilityStatus.LIMITED,      // turn-end less crisply observable
            rateLimitSignal = CapabilityStatus.LIMITED,     // only text-matched rate-limit line (scraping exception)
            coordination = CapabilityStatus.AVAILABLE,      // structured MCP tools (hub_send/inbox/watch)
            kind = ConnectorKind.MCP,
        )
    }
}

/**
 * One Connector-B session. The agent coordinates through [HubMcpTools]; the stream-json fields are stubs
 * because Connector B has no stdout stream and no stdin inject — its live transport (interactive Claude +
 * MCP) is the deferred, human-gated track (Doc 10 §6). [tools] is the proven, mock-testable surface.
 */
class McpSession(
    override val agentId: String,
    hub: Hub,
) : ConnectorSession {
    /** The MCP tools the agent calls AS this agent — the real, ACL/masking-enforced coordination path. */
    val tools = HubMcpTools(hub, agentId)

    // Deferred live transport: there is no stream-json stdout/stdin for Connector B. The interactive
    // Claude-Code attach feeds these later (separate auth-gated track); empty/no-op until then.
    override val events: Flow<StreamJsonEvent> = emptyFlow()
    override suspend fun sendTurn(turn: UserTurn) { /* B inbound = task pulled via hub_inbox; live attach deferred */ }
    override fun close() {}
}

/**
 * The hub-facing MCP tools a Connector-B agent calls (Doc 10 §5: `hub_send`/`hub_inbox`/`hub_watch`),
 * bound to one [agentId]. **Every tool routes through the SAME chokepoints as REST/mediation** — so the
 * ACL (canWrite/canRead) and [com.tneff.cyppieagents.comm.SecretMasker] masking are enforced identically,
 * not re-implemented (Tester-C4: 403 on a canWrite-denied channel + needle-absence at every egress). The
 * agent identity is the bound [agentId] (from the MCP server's auth), never a tool argument — so an agent
 * cannot send/read as another (Gate #1 identity, same stance as the connector-A mediation router).
 */
class HubMcpTools(
    private val hub: Hub,
    private val agentId: String,
) {
    /** `hub_send`: post into [channelId] AS the bound agent. Throws 403 (ForbiddenException) if the agent
     *  has no write access; the body is secret-masked inside [Hub.postAsAgent] (Gate #2/#3). */
    fun send(channelId: String, text: String, meta: MessageMeta? = null): Message =
        hub.postAsAgent(senderId = agentId, channelId = channelId, body = text, meta = meta)

    /** `hub_inbox`: the agent's ACL-filtered inbox across the channels it may read. */
    fun inbox(since: Long? = null): List<Message> = hub.inbox(agentId, since)

    /** `hub_watch`: live messages the agent may read — the same per-participant ACL filter as `/ws/comm`. */
    fun watch(): Flow<Message> = hub.events
        .filterIsInstance<MessageEvent>()
        .filter { hub.state.acl.canRead(it.message.channelId, agentId) }
        .map { it.message }
}
