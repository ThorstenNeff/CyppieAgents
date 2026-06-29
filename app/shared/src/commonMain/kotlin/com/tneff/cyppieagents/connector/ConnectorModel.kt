package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind

/**
 * CYP-123 (Epic CYP-118, Doc 10, UX-Spec CYP-119) — client-side helpers over the **`:core`** connector DTOs.
 *
 * The wire types ([Capabilities]/[CapabilityStatus]/[ConnectorKind]) live in `:core` (CYP-120) and the UI
 * renders them directly — no client-local duplicate, no drift (spec §8, exactly like `Message`/`Event`).
 * `Agent.capabilities` is **nullable**: `null` ⇒ "not yet reported" ⇒ **fail-closed** (badge present as unknown,
 * never silently "full" — spec §0/§5).
 */

/**
 * The five labelled capability dimensions (Doc 10 §3) plus a data-driven [UNKNOWN] fallback so a future wire
 * dimension renders generically instead of vanishing (spec §-Ask 4). [tagKey] is the camelCase, dot-free tag
 * segment = the exact `Capabilities` field name (tags.md); [UNKNOWN] carries the sanitized wire id.
 */
enum class CapabilityDimension(val tagKey: String) {
    STRUCTURED_USAGE("structuredUsage"),
    TOOL_GRANULARITY("toolGranularity"),
    RELIABLE_RESULT("reliableResult"),
    RATE_LIMIT_SIGNAL("rateLimitSignal"),
    COORDINATION("coordination"),
    UNKNOWN("unknown"),
}

/** One labelled dimension + its tri-state status, for the detailed panel / opt-in preview (spec §2.3). */
data class CapabilityRow(val dimension: CapabilityDimension, val status: CapabilityStatus)

/** The five dimensions in declared order (spec §2.3). Stable, no reflection — drives the panel + preview. */
val Capabilities.rows: List<CapabilityRow>
    get() = listOf(
        CapabilityRow(CapabilityDimension.STRUCTURED_USAGE, structuredUsage),
        CapabilityRow(CapabilityDimension.TOOL_GRANULARITY, toolGranularity),
        CapabilityRow(CapabilityDimension.RELIABLE_RESULT, reliableResult),
        CapabilityRow(CapabilityDimension.RATE_LIMIT_SIGNAL, rateLimitSignal),
        CapabilityRow(CapabilityDimension.COORDINATION, coordination),
    )

/**
 * True if **any** dimension is below [CapabilityStatus.AVAILABLE] — drives the compact fidelity badge, which is
 * present **only** when degraded (or caps are null), absent for a full-fidelity agent (spec §2.2, fail-closed
 * by absence like CYP-55). Honest aggregate, never optimistic.
 */
val Capabilities.isDegraded: Boolean
    get() = rows.any { it.status != CapabilityStatus.AVAILABLE }

/**
 * An operator's connector choice (write model). [riskAcknowledged] is the deliberate B opt-in act; the UI gates
 * the confirm on it and the **server is authoritative** (re-checks + audits for B — CYP-122). The write rides
 * the agent-spec (CYP-86 line), not a parallel store; stubbed until CYP-122.
 */
data class ConnectorSelection(val kind: ConnectorKind, val riskAcknowledged: Boolean) {
    /** B requires an explicit acknowledgment; A never does. The server re-checks (fail-closed). */
    val isAcknowledgmentSatisfied: Boolean
        get() = kind != ConnectorKind.MCP || riskAcknowledged
}

/** Uniform connector error carrying the server `:core` `ApiErrorBody` code at real-swap (e.g. `operator_required`). */
class ConnectorException(val code: String?) : Exception("connector error (${code ?: "?"})")
