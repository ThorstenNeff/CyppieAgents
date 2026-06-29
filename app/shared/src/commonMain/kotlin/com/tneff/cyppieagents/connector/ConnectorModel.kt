package com.tneff.cyppieagents.connector

/**
 * CYP-123 (Epic CYP-118, Doc 10) — client-local connector/capability model for the **commonMain** UI.
 *
 * Plain Kotlin on purpose: `:app:shared` has no kotlinx.serialization plugin and we do NOT pre-empt the
 * backend `:core` DTOs (CYP-120). At the stub→real swap these types are mapped 1:1 from `:core`; the field
 * names/order below mirror the **PO-locked** contract so the swap is a pure mapping with minimal churn.
 *
 * Real-swap gates (re-check against CYP-120/122):
 *  - exact field names/placement of [Capabilities] on the per-agent read-model (Agents DTO, beside `runState`);
 *  - the persistence form of [ConnectorKind] on the agent-spec (CYP-86 line) — NOT a parallel store;
 *  - the server-side opt-in **acknowledgment** shape (server-enforced + audited; the UI is only the affordance).
 */

/**
 * Tri-state fidelity of a single capability (Doc 10 §3). Order is worst-last so an aggregate can pick the
 * "worst" honestly ([Capabilities.overall]); never invents an [AVAILABLE] it can't prove (fail-closed).
 */
enum class CapabilityStatus { AVAILABLE, LIMITED, UNAVAILABLE }

/**
 * The five capability dimensions a connector MUST declare (Doc 10 §3). The mediator gates dependent
 * functions on these; the UI shows them honestly — degraded is marked, never faked.
 */
data class Capabilities(
    val structuredUsage: CapabilityStatus,   // token usage per turn → token thresholds & compact (06 §5)
    val toolGranularity: CapabilityStatus,   // tool.call/tool.result events → event-log depth (06)
    val reliableResult: CapabilityStatus,    // clean turn/result end → "agent done" detection, hand-off
    val rateLimitSignal: CapabilityStatus,   // structured rate-limit (vs text) → warden stall (07)
    val coordination: CapabilityStatus,      // how the agent talks to the hub → mediation (05 §2)
) {
    /** The five dimensions in declared order — for the detailed 5-row panel (stable, no reflection). */
    val dimensions: List<CapabilityDimension.Row>
        get() = listOf(
            CapabilityDimension.STRUCTURED_USAGE to structuredUsage,
            CapabilityDimension.TOOL_GRANULARITY to toolGranularity,
            CapabilityDimension.RELIABLE_RESULT to reliableResult,
            CapabilityDimension.RATE_LIMIT_SIGNAL to rateLimitSignal,
            CapabilityDimension.COORDINATION to coordination,
        ).map { (dim, status) -> CapabilityDimension.Row(dim, status) }

    /**
     * Worst status across all five (UNAVAILABLE > LIMITED > AVAILABLE) — drives the **compact** badge so a
     * single degraded dimension shows as degraded, not green. Honest aggregate, never optimistic.
     */
    fun overall(): CapabilityStatus = when {
        dimensions.any { it.status == CapabilityStatus.UNAVAILABLE } -> CapabilityStatus.UNAVAILABLE
        dimensions.any { it.status == CapabilityStatus.LIMITED } -> CapabilityStatus.LIMITED
        else -> CapabilityStatus.AVAILABLE
    }

    /** True if any dimension is below [CapabilityStatus.AVAILABLE] — the badge marks degradation. */
    val isDegraded: Boolean get() = overall() != CapabilityStatus.AVAILABLE
}

/** Stable identity of each capability dimension (for tags/labels); decoupled from the wire field order. */
enum class CapabilityDimension {
    STRUCTURED_USAGE, TOOL_GRANULARITY, RELIABLE_RESULT, RATE_LIMIT_SIGNAL, COORDINATION;

    /** camelCase, dot-free key for test tags (the tag grammar is `[A-Za-z0-9-]+` per segment). */
    val tagKey: String
        get() = when (this) {
            STRUCTURED_USAGE -> "structuredUsage"
            TOOL_GRANULARITY -> "toolGranularity"
            RELIABLE_RESULT -> "reliableResult"
            RATE_LIMIT_SIGNAL -> "rateLimitSignal"
            COORDINATION -> "coordination"
        }

    /** One labelled (dimension, status) pair for the detailed panel. */
    data class Row(val dimension: CapabilityDimension, val status: CapabilityStatus)
}

/**
 * The connector backing an agent (Doc 10 §1). [STREAM_JSON] (A) is the first-class default, full fidelity;
 * [MCP] (B) is the opt-in alternative with declared lower fidelity + declared risk — **never** pre-selected.
 */
enum class ConnectorKind { STREAM_JSON, MCP }

/**
 * Per-agent connector read-model for the steady-state display (badge + panel). Sourced from the agent
 * read-model (where `runState` lives, CYP-73) at real-swap; the stub serves it directly.
 */
data class AgentConnectorInfo(
    val agentId: String,
    val kind: ConnectorKind,
    val capabilities: Capabilities,
)

/**
 * An operator's connector choice for an agent (write). [riskAcknowledged] is the **deliberate opt-in act**
 * for [ConnectorKind.MCP]; the UI gates the confirm on it, but the **server is authoritative** — it enforces
 * the operator gate, requires the ack for B, and logs it as an audit event. Selecting [STREAM_JSON] needs no
 * ack. This rides the agent-spec write (CYP-86 line); it is NOT a parallel persistence store.
 */
data class ConnectorSelection(
    val kind: ConnectorKind,
    val riskAcknowledged: Boolean,
) {
    /** B requires an explicit risk acknowledgment; A never does. The server re-checks this (fail-closed). */
    val isAcknowledgmentSatisfied: Boolean
        get() = kind != ConnectorKind.MCP || riskAcknowledged
}

/** Uniform connector error carrying the server `:core` [ApiErrorBody] code at real-swap (e.g. `operator_required`). */
class ConnectorException(val code: String?) : Exception("connector error (${code ?: "?"})")
