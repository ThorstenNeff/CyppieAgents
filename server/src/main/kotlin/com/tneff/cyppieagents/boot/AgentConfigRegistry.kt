package com.tneff.cyppieagents.boot

import java.util.concurrent.ConcurrentHashMap

/** One agent's connector config (S14 / CYP-97): the bits not on the hub [com.tneff.cyppieagents.model.Agent]. */
data class AgentRuntimeConfig(val launch: String, val persona: String?)

/**
 * The mutable per-agent connector config (launch + persona), keyed by agent id (S14 / CYP-97). Shared
 * between [AgentManagement] (which mutates it on add/edit/remove) and the connector (which reads
 * [personaOf] at `open()` to write the worktree's CLAUDE.md) — a single source so the persona the
 * connector spawns with can't drift from what the operator saved. Seeded from `platform.config.json`.
 */
class AgentConfigRegistry(initial: List<AgentConfig> = emptyList()) {
    private val configs = ConcurrentHashMap<String, AgentRuntimeConfig>()

    init {
        initial.forEach { configs[it.id] = AgentRuntimeConfig(it.launch, it.claudeMd?.ifBlank { null }) }
    }

    /** The persona text (CLAUDE.md) for [agentId], or null — read by the connector at spawn time. */
    fun personaOf(agentId: String): String? = configs[agentId]?.persona

    fun configOf(agentId: String): AgentRuntimeConfig? = configs[agentId]

    fun put(agentId: String, launch: String, persona: String?) {
        configs[agentId] = AgentRuntimeConfig(launch, persona?.ifBlank { null })
    }

    fun remove(agentId: String) {
        configs.remove(agentId)
    }
}
