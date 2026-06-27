package com.tneff.cyppieagents.events

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
    }
}
