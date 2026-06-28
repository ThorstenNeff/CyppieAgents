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
    val TYPES: Set<String> = setOf(
        "stall.suspected", // CYP-61 (Scanner)
        "stall.recovered", // CYP-63 (Warden)
        "stall.escalated", // CYP-63 (Warden)
        "nudge.sent",      // CYP-62 (Actuator action)
        "po.escalated",    // CYP-62 (Actuator action: escalateToPO)
    )

    /** True if [wireType] is a Sense/Act-emitted type → not domain input, must not feed detectors. */
    fun isSignal(wireType: String): Boolean = wireType in TYPES
}
