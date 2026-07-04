package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-198 — a stored per-agent stream-json event: the durable agent-window transcript record + the `/ws/agent`
 * and `GET /api/agents/{id}/events` wire shape (shared `:core` so the client deserializes it identically).
 * [seq] is a gapless, monotonic, restart-surviving cursor — the client tracks the last [seq] it saw and
 * reconnects with `?since=<seq>` to replay-then-live without gap or dup. [event] is ALREADY masked (Gate #3).
 */
@Serializable
data class StoredAgentEvent(
    val seq: Long,
    val agentId: String,
    val projectId: String,
    val tsMs: Long,
    val event: StreamJsonEvent,
)
