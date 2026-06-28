package com.tneff.cyppieagents.scanner

/**
 * The controlled vocabulary of event types the Sense→Decide→Act stages **emit** as Signals/actions
 * (07 §4). Because the [Scanner] subscribes to the *whole* bus and emits signals back into it, an
 * event of one of these types is the loop's **own output** and must never be re-dispatched to the
 * detectors — otherwise a detector reacting to it would retrigger itself (07 §3 loop-avoidance).
 *
 * Membership is by the effective wire type (`Event.rawType ?: Event.type.wire`), so it works both
 * before CYP-64 enumerates these (carried via `rawType`) and after (carried via `type`). Future
 * watchers (budget, error-rate, security) add their signal/action types here as they land.
 */
object SignalVocabulary {
    /** The known signal/action types today (documentation + the loop-avoidance test iterates these). */
    val TYPES: Set<String> = setOf(
        "stall.suspected", // CYP-61 (Scanner detector)
        "stall.recovered", // CYP-63 (Stall policy: recovery)
        "stall.escalated", // CYP-63 (Stall policy: escalateToPO action)
        "nudge.sent",      // CYP-62 (Actuator: nudge action)
    )

    /**
     * The Sense/Act action-event **families** (07 §4). Matching by suffix/prefix — not a fixed list —
     * means a future watcher's `budget.suspected` / `budget.escalated` / `budget.recovered` /
     * `nudge.*` is loop-avoidance-covered automatically, with no edit here. None of the domain wire
     * types (`turn.start`, `tool.call`, `error.ratelimit`, …) end in these suffixes, so nothing real
     * is mis-filtered.
     */
    private val SIGNAL_SUFFIXES = listOf(".suspected", ".recovered", ".escalated")

    /** True if [wireType] is a Sense/Act-emitted type → not domain input, must not feed detectors. */
    fun isSignal(wireType: String): Boolean =
        wireType in TYPES || wireType.startsWith("nudge.") || SIGNAL_SUFFIXES.any { wireType.endsWith(it) }
}
