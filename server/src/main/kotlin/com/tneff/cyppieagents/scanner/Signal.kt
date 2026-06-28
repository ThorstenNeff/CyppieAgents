package com.tneff.cyppieagents.scanner

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
    /** team = project token (05 §3); MVP single-team. */
    val teamId: String,
    /** ties the signal to the work-run that stalled (07 §3); null if not correlated. */
    val correlationId: String? = null,
    val evidence: JsonObject = JsonObject(emptyMap()),
)
