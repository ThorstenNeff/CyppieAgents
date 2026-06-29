package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.Capabilities
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-agent connector capabilities, populated at boot (CYP-121). The Mediator's fidelity-gated
 * functions (projector event-log depth, context-usage bander, Warden stall detector) resolve an
 * agent's [Capabilities] through [get] and consult [com.tneff.cyppieagents.model.CapabilityGate].
 *
 * MVP = one connector for all agents, so every booted agent maps to the same declared capabilities;
 * CYP-122 makes the connector (and thus the entry) per-agent — additively, no consumer change, since
 * the consumers already key by agentId. A null [get] result means "capabilities unknown" → the
 * consumers default to *enabled* (legacy behaviour preserved for installs/tests with no registry).
 */
class CapabilityRegistry {
    private val byAgent = ConcurrentHashMap<String, Capabilities>()

    fun set(agentId: String, caps: Capabilities) {
        byAgent[agentId] = caps
    }

    fun get(agentId: String): Capabilities? = byAgent[agentId]
}
