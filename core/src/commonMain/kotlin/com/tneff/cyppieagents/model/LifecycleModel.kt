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
    /**
     * CYP-421 (b) — WHY, when [runState] == [AgentRunState.ERROR]: a finite [AgentErrorCode], never free text
     * (the content-free boundary above holds — a code is not a body). **Non-null ONLY on ERROR**, single-sourced
     * in `LifecycleManager.setRunState`; null for RUNNING/STOPPED and omitted on the wire (CommJson
     * `explicitNulls = false`), so non-ERROR frames keep the exact `{agentId, runState}` shape. Additive +
     * defaulted so older payloads still decode (CommJson `ignoreUnknownKeys = true`) — an un-updated consumer
     * ignores it, never throws.
     */
    val errorCode: AgentErrorCode? = null,
)

/**
 * CYP-316 — the single message pushed over `/ws/token-usage`: an agent's current context-window
 * occupancy (token count), for the live display in the AgentWindow title bar.
 *
 * **Content-free by construction** — only [agentId] and a token *count* ([contextTokens]), never any
 * text/body. It is a **momentary latest-wins value**, NOT an append log: [contextTokens] is the newest
 * turn's context size = `input + cache_read + cache_creation` tokens (output excluded — not standing
 * context), single-sourced from the CYP-36 `UsageSnapshot.contextTokens`.
 *
 * `contextTokens == null` means **no trustworthy value**: a Connector-B / non-`structuredUsage` agent
 * (its token counts are too coarse — never a faked 0), or a freshly (re)started agent before its first
 * turn (a restart resets to `null` until the next result). The client shows a number only when non-null.
 *
 * On connect the server emits one per agent (the current snapshot); thereafter one per change. Because
 * it is latest-wins, a reconnect snapshot is **idempotent** — the client upserts by [agentId], never
 * appends, so a re-delivered snapshot neither duplicates nor loses.
 */
@Serializable
data class AgentTokenUsageEvent(
    val agentId: String,
    val contextTokens: Int? = null,
)

/**
 * CYP-324 — the single message pushed over `/ws/busy-state`: whether an agent is currently **processing a
 * turn**. Drives the `*` in the AgentWindow title bar (busy) vs. clear (idle).
 *
 * **Content-free by construction** — only [agentId] and a [busy] boolean, never any text/body. Like the
 * lifecycle/token feeds it is a **momentary latest-wins value**, NOT an append log: [busy] is the newest
 * state, derived from REAL session state (a turn is in flight between the connector's `turn.start` and its
 * `result` / process-exit — not a heuristic).
 *
 * `busy == true` from turn injection until that turn's result (covering the whole tool-use loop); `false`
 * on the result, on process-exit, and on stop/restart (so a dead or stopped agent never hangs busy).
 *
 * On connect the server emits one per agent (the current snapshot); thereafter one per change. Because it
 * is latest-wins, a reconnect snapshot is **idempotent** — the client upserts by [agentId], never appends,
 * so a reconnect mid-turn correctly re-delivers `busy = true` and neither duplicates nor loses.
 */
@Serializable
data class AgentBusyStateEvent(
    val agentId: String,
    val busy: Boolean,
)
