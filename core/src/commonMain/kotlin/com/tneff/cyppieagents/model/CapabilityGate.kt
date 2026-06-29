package com.tneff.cyppieagents.model

/**
 * The pure capability-gating decision (Doc 10 §3 / CYP-121) — the connector-fidelity analogue of
 * [AclMatrix]/[ProjectScope]: it holds no state and does no I/O, so the rule lives in one place and is
 * testable in isolation, then consumed identically by every Mediator function that depends on a
 * capability (the projector's event-log depth, the context-usage bander, the Warden stall detector).
 *
 * The decision is an **explicit (dimension × status) table** ([mode]) — never a binary "AVAILABLE vs
 * the rest" collapse, so the three-status semantics stay visible and each status is pinned by its own
 * test. Two invariants the table makes structural:
 *  - **Fail-closed (F2):** unknown caps — `null`, i.e. an unresolved/registry-miss agent — map to the
 *    safe branch ([CapabilityMode.OFF]), **never** assumed AVAILABLE. "No capability system configured"
 *    is a *different* case handled by the consumer (it simply doesn't call the gate); a configured
 *    system that can't resolve an agent fails closed.
 *  - **Conservative LIMITED (CYP-121):** for the enforced dimensions LIMITED currently maps to OFF — an
 *    *explicit, documented* entry, not a hidden collapse. The real *degraded-running* path
 *    ([CapabilityMode.DEGRADED]) is reserved for CYP-122, when Connector B declares LIMITED for real;
 *    changing the LIMITED output there is a deliberate table + test change, not silent drift.
 */
object CapabilityGate {

    /** The three dimensions CYP-121 ENFORCES. reliableResult + coordination are log-only here (their
     *  sole real consumer is Connector B); their enforcement lands in CYP-122. */
    enum class EnforcedCapability { RATE_LIMIT_SIGNAL, STRUCTURED_USAGE, TOOL_GRANULARITY }

    /** ENABLED = run fully · OFF = gated off (don't run) · DEGRADED = run reduced/marked (CYP-122). */
    enum class CapabilityMode { ENABLED, DEGRADED, OFF }

    /**
     * The explicit per-(dimension, status) enforcement decision. [caps] = null means the agent's
     * capabilities are unknown/unresolved → fail-closed to OFF.
     */
    fun mode(capability: EnforcedCapability, caps: Capabilities?): CapabilityMode {
        val status: CapabilityStatus? = caps?.let { statusOf(capability, it) }
        // Per-status branch is explicit on purpose (F1): LIMITED and UNAVAILABLE are NOT collapsed, and
        // the null (unknown) branch is its own fail-closed entry (F2).
        return when (status) {
            CapabilityStatus.AVAILABLE -> CapabilityMode.ENABLED
            // CYP-121 conservative: LIMITED → OFF. CYP-122 → CapabilityMode.DEGRADED (real degraded-running).
            CapabilityStatus.LIMITED -> CapabilityMode.OFF
            CapabilityStatus.UNAVAILABLE -> CapabilityMode.OFF
            null -> CapabilityMode.OFF // fail-closed: unknown caps are never assumed AVAILABLE (F2)
        }
    }

    /** Convenience: does the [capability]-fed function run fully? (OFF/DEGRADED both gate it in CYP-121.) */
    fun isEnabled(capability: EnforcedCapability, caps: Capabilities?): Boolean =
        mode(capability, caps) == CapabilityMode.ENABLED

    private fun statusOf(capability: EnforcedCapability, caps: Capabilities): CapabilityStatus = when (capability) {
        EnforcedCapability.RATE_LIMIT_SIGNAL -> caps.rateLimitSignal
        EnforcedCapability.STRUCTURED_USAGE -> caps.structuredUsage
        EnforcedCapability.TOOL_GRANULARITY -> caps.toolGranularity
    }

    /**
     * The (dimension, status) pairs that are NOT AVAILABLE across **all five** dimensions — exactly what
     * gets logged as `capability.degraded` (Doc 10 §3), including the log-only reliableResult/coordination.
     * Single-sources the five dimension names so the log/UI label and the gate can't drift. Dimension
     * strings match the [Capabilities] wire field names.
     */
    fun degraded(caps: Capabilities): List<DimensionStatus> = listOf(
        DimensionStatus("structuredUsage", caps.structuredUsage),
        DimensionStatus("toolGranularity", caps.toolGranularity),
        DimensionStatus("reliableResult", caps.reliableResult),
        DimensionStatus("rateLimitSignal", caps.rateLimitSignal),
        DimensionStatus("coordination", caps.coordination),
    ).filter { it.status != CapabilityStatus.AVAILABLE }
}

/** A single capability dimension and its declared fidelity — the unit logged on degradation. */
data class DimensionStatus(val dimension: String, val status: CapabilityStatus)
