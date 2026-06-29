package com.tneff.cyppieagents.model

/**
 * The pure capability-gating decision (Doc 10 §3 / CYP-121) — the connector-fidelity analogue of
 * [AclMatrix]/[ProjectScope]: it holds no state and does no I/O, so the rule lives in one place and is
 * testable in isolation, then consumed identically by every Mediator function that depends on a
 * capability (the projector's event-log depth, the context-usage bander, the Warden stall detector).
 *
 * **Enforcement semantics (CYP-121, conservative):** a fidelity-dependent function runs only when its
 * dimension is fully [CapabilityStatus.AVAILABLE]. LIMITED and UNAVAILABLE both gate the function OFF —
 * we never run a function on a signal we cannot fully trust ("nie vorgetäuscht"). A true
 * *degraded-but-running* path per function (e.g. coarse usage, text-matched rate-limit) is deliberate
 * future work; CYP-121 enforces three dimensions (rateLimitSignal/structuredUsage/toolGranularity) and
 * *logs* degradation for all five via [degraded] so the gap is always visible.
 */
object CapabilityGate {

    /** A function fed by [status] is enabled only when the dimension is fully available. */
    fun enabled(status: CapabilityStatus): Boolean = status == CapabilityStatus.AVAILABLE

    /**
     * The (dimension, status) pairs that are NOT AVAILABLE — exactly what gets logged as
     * `capability.degraded` (Doc 10 §3). Single-sources the five dimension names so the log/UI label and
     * the gate can't drift. Dimension strings match the [Capabilities] wire field names.
     */
    fun degraded(caps: Capabilities): List<DimensionStatus> = listOf(
        DimensionStatus("structuredUsage", caps.structuredUsage),
        DimensionStatus("toolGranularity", caps.toolGranularity),
        DimensionStatus("reliableResult", caps.reliableResult),
        DimensionStatus("rateLimitSignal", caps.rateLimitSignal),
        DimensionStatus("coordination", caps.coordination),
    ).filter { !enabled(it.status) }
}

/** A single capability dimension and its declared fidelity — the unit logged on degradation. */
data class DimensionStatus(val dimension: String, val status: CapabilityStatus)
