package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `context.usage` banding (PRD §4.2, ST2/CYP-36). `context.usage` is potentially every turn of every
 * agent and would dominate the DB, so we hold the **fill level per agent in-memory** and persist an
 * event ONLY when something meaningful changes:
 *  - the fill crosses up into a new **10%-band** (configurable [bandPctWidth]), or
 *  - the fill crosses up through the **compact threshold** ([compactPct], the configured
 *    `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`, e.g. 75) — which need not land on a band boundary.
 *
 * Pure decision: [onUsage] returns the [EventDraft]s to record (0, 1, or 2), holding no sink. The
 * caller (ST3 mediator wiring) records them via [EventRecorder]. Content-free: `detail` carries only
 * numbers (PRD §3.5).
 *
 * Denominator note (surfaced to PO): `ResultEvent.usage` gives token *counts*, not a percentage, so
 * fill% needs a context-window size. [contextWindowTokens] is that knob (default 200k); it will be
 * wired from the `events` config block in CYP-43. It is not in PRD §8's list — flagged, not invented.
 */
class ContextUsageBander(
    private val contextWindowTokens: Long = DEFAULT_CONTEXT_WINDOW_TOKENS,
    private val bandPctWidth: Int = DEFAULT_BAND_PCT,
    private val compactPct: Int = DEFAULT_COMPACT_PCT,
) {
    init {
        require(contextWindowTokens > 0) { "contextWindowTokens must be > 0" }
        require(bandPctWidth in 1..100) { "bandPctWidth must be 1..100" }
        require(compactPct in 1..100) { "compactPct must be 1..100" }
    }

    private val lock = Any()
    private val lastBandByAgent = HashMap<String, Int>() // highest band already emitted (multiple of width)
    private val compactArmedByAgent = HashMap<String, Boolean>() // true while below compactPct → next up-cross emits

    /**
     * Feed one turn-end usage snapshot for [agentId]. Returns the events to persist for any upward
     * band crossing and/or compact-threshold crossing — empty when nothing meaningful changed.
     */
    fun onUsage(
        agentId: String,
        projectId: String,
        snapshot: UsageSnapshot,
        sessionId: String? = null,
        correlationId: String? = null,
    ): List<EventDraft> {
        // fill% of the context window; output tokens are excluded (not standing context).
        val fillPct = snapshot.contextTokens * 100.0 / contextWindowTokens
        val band = ((fillPct / bandPctWidth).toInt() * bandPctWidth).coerceIn(0, 100)

        val drafts = ArrayList<EventDraft>(2)
        synchronized(lock) {
            val prevBand = lastBandByAgent[agentId] ?: 0
            when {
                band > prevBand && band >= bandPctWidth -> {
                    // Crossed up into a new band → record the new band (the highest reached).
                    lastBandByAgent[agentId] = band
                    drafts += usageDraft(agentId, projectId, sessionId, correlationId, band, fillPct, snapshot, Reason.BAND)
                }
                band < prevBand -> {
                    // Context shrank (e.g. after a compaction) → reset so a later climb re-emits. No event.
                    lastBandByAgent[agentId] = band
                }
            }

            val armed = compactArmedByAgent[agentId] ?: true
            if (fillPct >= compactPct && armed) {
                compactArmedByAgent[agentId] = false
                drafts += usageDraft(agentId, projectId, sessionId, correlationId, compactPct, fillPct, snapshot, Reason.COMPACT)
            } else if (fillPct < compactPct && !armed) {
                compactArmedByAgent[agentId] = true // re-arm once we drop back below the threshold
            }
        }
        return drafts
    }

    /** Drop an agent's fill state (e.g. on session recycle / agent stop). */
    fun reset(agentId: String) {
        synchronized(lock) {
            lastBandByAgent.remove(agentId)
            compactArmedByAgent.remove(agentId)
        }
    }

    private enum class Reason(val wire: String) { BAND("band"), COMPACT("compact") }

    private fun usageDraft(
        agentId: String,
        projectId: String,
        sessionId: String?,
        correlationId: String?,
        bandPct: Int,
        fillPct: Double,
        s: UsageSnapshot,
        reason: Reason,
    ) = EventDraft(
        agentId = agentId,
        projectId = projectId,
        type = EventType.CONTEXT_USAGE,
        // Compact threshold is a "we're near the limit" signal → warn; ordinary band steps are info.
        severity = if (reason == Reason.COMPACT) Severity.WARN else Severity.INFO,
        sessionId = sessionId,
        correlationId = correlationId,
        detail = buildJsonObject {
            put("reason", reason.wire)
            put("bandPct", bandPct)
            put("fillPct", (fillPct * 10).toInt() / 10.0) // one decimal, content-free
            put("inputTokens", s.inputTokens)
            put("outputTokens", s.outputTokens)
            put("cacheReadTokens", s.cacheReadTokens)
            put("cacheCreationTokens", s.cacheCreationTokens)
            put("contextTokens", s.contextTokens)
        },
    )

    companion object {
        const val DEFAULT_BAND_PCT = 10
        const val DEFAULT_COMPACT_PCT = 75
        const val DEFAULT_CONTEXT_WINDOW_TOKENS = 200_000L
    }
}
