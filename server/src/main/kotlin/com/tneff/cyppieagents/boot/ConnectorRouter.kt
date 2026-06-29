package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.Connector
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.McpConnector
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo

/**
 * Per-agent connector selection (CYP-122). Each agent declares a [ConnectorKind] (`STREAM_JSON` = Connector
 * A, `MCP` = Connector B); this router resolves the agent's kind at spawn time via [kindOf] and delegates
 * to the matching [Connector]. It is itself a [Connector], so it plugs straight into the spawn path that
 * previously held a single connector (and through the CYP-120 `connectorFactory` seam) with no other change.
 *
 * [capabilitiesFor] is overridden to return the **serving** connector's capabilities for an agent, so the
 * boot caps-population + Mediator gating see each agent's real fidelity (A all-AVAILABLE, B column-B).
 * Adding a third connector later (Webhook/Remote) is one more branch here — additive.
 */
class ConnectorRouter(
    private val streamJson: Connector,
    private val mcp: Connector,
    private val kindOf: (agentId: String) -> ConnectorKind,
) : Connector {

    /** The connector serving [agentId], by its declared kind. */
    fun forAgent(agentId: String): Connector = when (kindOf(agentId)) {
        ConnectorKind.STREAM_JSON -> streamJson
        ConnectorKind.MCP -> mcp
    }

    // The router has no single "own" capability — default to A's so a stray call is the safe full-fidelity
    // default; real per-agent fidelity comes through capabilitiesFor (what boot + the gates use).
    override val capabilities: Capabilities get() = streamJson.capabilities

    override fun capabilitiesFor(agentId: String): Capabilities = forAgent(agentId).capabilities

    // CYP-137: like capabilities — the router has no own provider; resolve the serving connector's per agent.
    override val provider: ProviderInfo get() = streamJson.provider

    override fun providerFor(agentId: String): ProviderInfo = forAgent(agentId).provider

    // CYP-140: the trust of the connector actually serving the agent (per-agent, like capabilities).
    override val trust: com.tneff.cyppieagents.model.ConnectorTrust get() = streamJson.trust

    override fun trustFor(agentId: String): com.tneff.cyppieagents.model.ConnectorTrust = forAgent(agentId).trust

    override fun open(agentId: String): ConnectorSession = forAgent(agentId).open(agentId)

    override fun open(agentId: String, worktreeName: String): ConnectorSession =
        forAgent(agentId).open(agentId, worktreeName)

    companion object {
        /** The declared capability profile for a [ConnectorKind] — single-sourced from the connector impls
         *  (A all-AVAILABLE, B column-B). Used by the opt-in to re-declare an agent's caps without a respawn. */
        fun capabilitiesForKind(kind: ConnectorKind): Capabilities = when (kind) {
            ConnectorKind.STREAM_JSON -> ClaudeCodeConnector.STREAM_JSON_CAPABILITIES
            ConnectorKind.MCP -> McpConnector.MCP_CAPABILITIES
        }
    }
}
