package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.ConnectorKind
import java.util.concurrent.ConcurrentHashMap

/** One agent's connector config (S14 / CYP-97 + CYP-122): the bits not on the hub [com.tneff.cyppieagents.model.Agent]. */
data class AgentRuntimeConfig(
    val launch: String,
    val persona: String?,
    /** Which connector serves this agent (Doc 10 §1 / CYP-122). Default Connector A. */
    val connectorKind: ConnectorKind = ConnectorKind.STREAM_JSON,
)

/**
 * The mutable per-agent connector config (launch + persona + connectorKind), keyed by agent id (S14 /
 * CYP-97, CYP-122). Shared between [AgentManagement] (which mutates it on add/edit/remove/opt-in) and the
 * connector layer (the connector reads [personaOf] at `open()`; the [ConnectorRouter] reads
 * [connectorKindOf] at spawn) — a single source so the persona/connector the agent spawns with can't drift
 * from what the operator saved. Seeded from `platform.config.json`.
 */
class AgentConfigRegistry(initial: List<AgentConfig> = emptyList()) {
    private val configs = ConcurrentHashMap<String, AgentRuntimeConfig>()

    init {
        initial.forEach { configs[it.id] = AgentRuntimeConfig(it.launch, it.claudeMd?.ifBlank { null }, it.connectorKind) }
    }

    /** The persona text (CLAUDE.md) for [agentId], or null — read by the connector at spawn time. */
    fun personaOf(agentId: String): String? = configs[agentId]?.persona

    /** The connector kind serving [agentId] (CYP-122); default Connector A for an unknown agent. */
    fun connectorKindOf(agentId: String): ConnectorKind = configs[agentId]?.connectorKind ?: ConnectorKind.STREAM_JSON

    fun configOf(agentId: String): AgentRuntimeConfig? = configs[agentId]

    /** Write launch + persona (CYP-97 edit). **Preserves** the stored [AgentRuntimeConfig.connectorKind] —
     *  a connector change is the dedicated, audited opt-in ([setConnectorKind]), never a side effect of an edit. */
    fun put(agentId: String, launch: String, persona: String?) {
        val kind = configs[agentId]?.connectorKind ?: ConnectorKind.STREAM_JSON
        configs[agentId] = AgentRuntimeConfig(launch, persona?.ifBlank { null }, kind)
    }

    /** Register a new agent's config (CYP-97 add), with its declared [connectorKind] (CYP-122). */
    fun put(agentId: String, launch: String, persona: String?, connectorKind: ConnectorKind) {
        configs[agentId] = AgentRuntimeConfig(launch, persona?.ifBlank { null }, connectorKind)
    }

    /** Set the connector kind for [agentId] (CYP-122 opt-in). Effective at the next spawn (CYP-73 restart). */
    fun setConnectorKind(agentId: String, connectorKind: ConnectorKind) {
        configs.compute(agentId) { _, prev ->
            (prev ?: AgentRuntimeConfig(launch = "claude", persona = null)).copy(connectorKind = connectorKind)
        }
    }

    fun remove(agentId: String) {
        configs.remove(agentId)
    }
}
