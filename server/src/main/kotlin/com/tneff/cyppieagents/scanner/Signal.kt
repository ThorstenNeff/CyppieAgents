package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.model.Event
import kotlinx.serialization.json.JsonObject

/**
 * A raw pattern a [Detector] found in the event stream (07 §3). A Signal is **not** an action — it is
 * a *suspicion* the Warden later decides on. Signals are emitted back into the Event-Log bus as
 * Events (07 §2, "Signale sind selbst Events"), so the whole Sense→Decide→Act loop is visible in the
 * same Browse/Live-Tail (06) with no separate transport.
 *
 * [evidence] is **content-free** metadata (which events led here, timestamps, the rate-limit status)
 * — the same metadata-only stance as `EventDraft.detail`. Modelled as a [JsonObject] (not the spec's
 * loose `Map<String, Any?>`) so it serializes deterministically and drops straight into the event
 * `detail` when emitted.
 */
data class Signal(
    /** Controlled vocabulary, e.g. `"stall.suspected"` (07 §4). Maps to the emitted Event's type. */
    val type: String,
    val agentId: String,
    /** Active project / tenant carried from the source event (S12 / CYP-83 — keeps the Scanner-on-the-bus
        project-aware / N-mediator-capable; 05 §3 / Doc 07). MVP = 1 project. */
    val projectId: String,
    /** ties the signal to the work-run that stalled (07 §3); null if not correlated. */
    val correlationId: String? = null,
    val evidence: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        /**
         * Reconstruct the [Signal] a signal-type Event carries — the inverse of emitting a Signal as
         * an Event (07 §2). The Warden consumes signal Events off the bus and routes them with this.
         * Returns `null` for any non-signal event (effective wire type not in [SignalVocabulary]), so a
         * domain event never looks like a signal. `detail` is carried back verbatim as [evidence].
         */
        fun fromEvent(e: Event): Signal? {
            val type = e.rawType ?: e.type.wire
            if (!SignalVocabulary.isSignal(type)) return null
            return Signal(
                type = type,
                agentId = e.agentId,
                projectId = e.projectId,
                correlationId = e.correlationId,
                evidence = e.detail,
            )
        }
    }
}
