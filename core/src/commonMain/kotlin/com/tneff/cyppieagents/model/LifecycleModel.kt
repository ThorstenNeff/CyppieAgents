package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * The single message pushed over `/ws/lifecycle` (CYP-73): a per-agent process-status update.
 *
 * **Content-free by construction** — it carries only [agentId] and the [status] enum, never event
 * details / bodies / metadata. That is the whole point: the AgentWindow header is always visible
 * (not operator-gated), so its live feed must NOT reuse the operator-gated `/ws/events` egress. This
 * narrow DTO is the separate, leak-free path (same boundary as the CYP-55 badge).
 *
 * On connect the server emits one of these per agent (the current snapshot); thereafter one per
 * stop/start/restart/error transition (the deltas). The client upserts by [agentId].
 */
@Serializable
data class AgentRunStateEvent(
    val agentId: String,
    val runState: AgentRunState,
)
