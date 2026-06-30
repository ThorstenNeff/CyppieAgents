package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * S5 / G4 — maps an **untrusted** [WireEvent] (a remote's self-report) to a content-free [EventDraft] for
 * the Event-Log. The bridge is untrusted (a malicious user can handcraft the frame), so EVERY field is
 * treated as an opaque attacker string and the SERVER enforces (G4-5):
 *  - **whitelist-DROP** — only the exact [EventProjector.RATE_LIMIT_KEYS] are kept; any other key is dropped
 *    (not just shape-validated). Single-sourced from the projector so the local + remote rate-limit shape
 *    can't drift.
 *  - **size-cap** — every kept value AND the tool name are capped ([VALUE_CAP]) → no secret / 10-MB
 *    smuggling in `rateLimit["status"]` or `tool`.
 *  - **opaque labels** — values are stored verbatim (capped) as display strings; never parsed/executed.
 *  - **`source=remote` is SERVER-stamped** (the wire provenance, token-derived) — never a frame field; a
 *    malicious bridge cannot claim `LOCAL`.
 *  - it **NEVER touches the capability clamp** (G4-4: caps are `WireHello`-only).
 */
object WireEventIngest {
    /** G4-5 — per-value / tool-name size cap (an opaque attacker string can't smuggle a secret or DoS). */
    const val VALUE_CAP = 256

    private fun cap(s: String): String = if (s.length > VALUE_CAP) s.take(VALUE_CAP) else s

    fun toDraft(agentId: String, projectId: String, event: WireEvent): EventDraft = when (event.signal) {
        WireEventType.RATE_LIMIT -> EventDraft(
            agentId, projectId, EventType.ERROR_RATELIMIT, Severity.WARN,
            detail = buildJsonObject {
                put("source", "remote") // server-stamped provenance (NOT a frame field)
                // whitelist-DROP + size-cap: ONLY the projector's rate-limit keys survive, values capped.
                val rl = event.rateLimit
                if (rl != null) for (key in EventProjector.RATE_LIMIT_KEYS) rl[key]?.let { put(key, cap(it)) }
            },
        )
        WireEventType.TOOL_CALL -> EventDraft(
            agentId, projectId, EventType.TOOL_CALL, Severity.INFO,
            detail = buildJsonObject {
                put("source", "remote")
                event.tool?.let { put("toolName", cap(it)) } // opaque, size-capped — never the tool I/O
            },
        )
        WireEventType.TOOL_RESULT -> EventDraft(
            agentId, projectId, EventType.TOOL_RESULT, Severity.INFO,
            detail = buildJsonObject {
                put("source", "remote")
                event.tool?.let { put("toolName", cap(it)) }
            },
        )
    }
}
