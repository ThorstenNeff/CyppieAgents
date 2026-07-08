package com.tneff.cyppieagents.events

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Content-free token counts from a turn-end `ResultEvent.usage` (PRD §4.2 data source, verified
 * shape CYP-5/CYP-13). Pure numbers — no text ever crosses into the Event-Log via this path.
 *
 * [contextTokens] is the occupancy that matters for the autocompact threshold: the prompt/context
 * size = non-cached input + cache-read + cache-creation. Fresh `output_tokens` are reported for
 * cost/visibility but are not part of the standing context, so they don't count toward fill.
 */
data class UsageSnapshot(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
) {
    val contextTokens: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens

    companion object {
        /**
         * Parse the Anthropic usage object tolerantly — any missing/odd field becomes 0, never
         * throws (the usage shape is CLI-version-sensitive, same stance as the rest of the connector).
         * This is the bridge ST3 (CYP-37) will use to feed real `ResultEvent.usage` into the bander.
         */
        fun fromUsageJson(usage: JsonObject?): UsageSnapshot {
            fun n(key: String): Long = (usage?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L
            return UsageSnapshot(
                inputTokens = n("input_tokens"),
                outputTokens = n("output_tokens"),
                cacheReadTokens = n("cache_read_input_tokens"),
                cacheCreationTokens = n("cache_creation_input_tokens"),
            )
        }

        /**
         * CYP-325 — the canonical context occupancy from a stream-json `result.usage` object, reading the
         * TRUE current context size, not the 2–5M turn-aggregate. This is the stable seam the title-bar feed
         * (and the tester's teeth) build on. Empirically pinned (capture, CLI 2.1.204): a multi-tool-use turn
         * carries a per-iteration breakdown `usage.iterations[]`, and each top-level count is the SUM across
         * those iterations — so the current context size is the **last** iteration's occupancy. Version-tolerant:
         *  - `iterations[]` present → the last iteration's occupancy (the fix);
         *  - absent (single-iteration, or an older/edge CLI) → the top-level occupancy as a **degraded**
         *    fallback (equal to iterations.last when single-iteration; the un-fixed legacy value otherwise).
         * Returns `null` for a missing object. Occupancy = `input + cache_read + cache_creation` (output
         * excluded — not standing context). Single-sourced on [contextTokens] so the two can't drift.
         */
        fun contextTokensFromUsage(usage: JsonObject?): Long? =
            if (usage == null) null else snapshotFromUsage(usage).contextTokens

        /**
         * CYP-325 — the wire-tolerant [UsageSnapshot] behind [contextTokensFromUsage]: the LAST iteration's
         * usage (`iterations[-1]`) when present, else the top-level object (degraded fallback; zeros for a
         * null object). This is the SINGLE source for BOTH the title-bar number ([contextTokensFromUsage])
         * AND the [ContextUsageBander] fill%/band — so the band and the number can't disagree on the same
         * turn (they were both wrong before, reading the summed top level; now both read the last iteration).
         */
        fun snapshotFromUsage(usage: JsonObject?): UsageSnapshot {
            val effective = (usage?.get("iterations") as? JsonArray)?.lastOrNull() as? JsonObject ?: usage
            return fromUsageJson(effective)
        }
    }
}
