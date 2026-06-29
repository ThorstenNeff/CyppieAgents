package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.ProviderInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-agent connector provider (E2.1 / CYP-137), populated at boot from each agent's connector
 * ([Connector.providerFor]). `GET /api/agents` fills the `Agent.provider` field from [get], so the UI
 * can show the tool subordinate to identity ("PO (Claude)"). Mirrors [CapabilityRegistry]: per-agent,
 * additive, no consumer change (keyed by agentId). A null [get] = provider unknown → the field stays
 * null (fail-closed: the UI omits the qualifier, never guesses).
 */
class ProviderRegistry {
    private val byAgent = ConcurrentHashMap<String, ProviderInfo>()

    fun set(agentId: String, provider: ProviderInfo) {
        byAgent[agentId] = provider
    }

    fun get(agentId: String): ProviderInfo? = byAgent[agentId]
}
