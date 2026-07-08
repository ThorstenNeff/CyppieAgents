package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.boot.EventsConfig
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-325 — the title-bar token display showed 2–5M (and jumped backward) because CYP-316 fed the
 * **top-level** `result.usage` counts, which SUM across the turn's server-side tool-use iterations. The fix
 * reads the TRUE current context size = the **last** iteration (`result.usage.iterations[-1]`), occupancy =
 * `input + cache_read + cache_creation` (output excluded), sanity-clamped to the real ~1M window.
 *
 * These teeth build on the wire-tolerant capsule [UsageSnapshot.contextTokensFromUsage] — a stable seam that
 * survives the empirical field pinning (the QA multi-turn tooth also hangs off it).
 *
 * Mutations that red these:
 *  - feed the top-level sum instead of iterations.last → the 25k/40k assertions red (would be 75k / the sum);
 *  - drop the 200k→1M window correction → the clamp assertion reds (a legit >200k occupancy would be capped);
 *  - include output_tokens in the occupancy → the exact-value assertion reds.
 */
class Cyp325TokenContextSizeTest {

    private fun caps(usage: CapabilityStatus) = Capabilities(
        structuredUsage = usage,
        toolGranularity = CapabilityStatus.AVAILABLE,
        reliableResult = CapabilityStatus.AVAILABLE,
        rateLimitSignal = CapabilityStatus.AVAILABLE,
        coordination = CapabilityStatus.AVAILABLE,
        kind = ConnectorKind.MCP,
    )

    private fun result(usageJson: String): StreamJsonEvent = CommJson.decodeFromString(
        """{"type":"result","subtype":"success","is_error":false,"session_id":"s","uuid":"u","usage":$usageJson}""",
    )

    private fun usage(json: String): JsonObject = CommJson.decodeFromString(json)

    /** Project each event and collect the values pushed to the onContextTokens sink (ENABLED unless overridden). */
    private fun pushes(vararg events: StreamJsonEvent, resolve: ((String) -> Capabilities?)? = { caps(CapabilityStatus.AVAILABLE) }): List<Int?> {
        val out = ArrayList<Int?>()
        val p = EventProjector(ContextUsageBander(), projectId = "t", capabilities = resolve, onContextTokens = { _, t -> out.add(t) })
        events.forEach { p.project("backend", "s", "c", it) }
        return out
    }

    // A single turn with THREE tool-use iterations. Top-level input_tokens (75000) is the SUM (the 2–5M bug at
    // scale); the current context size is the LAST iteration's occupancy = 20000 + 4000 + 1000 = 25000.
    private val multiIterTurn = """
        {"input_tokens":75000,"cache_read_input_tokens":9000,"cache_creation_input_tokens":2500,"output_tokens":1200,
         "iterations":[
           {"input_tokens":10000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":400},
           {"input_tokens":40000,"cache_read_input_tokens":5000,"cache_creation_input_tokens":1500,"output_tokens":400},
           {"input_tokens":20000,"cache_read_input_tokens":4000,"cache_creation_input_tokens":1000,"output_tokens":400}
         ]}
    """.trimIndent()

    @Test
    fun multiToolUseTurn_feedsLastIterationOccupancy_notTheTurnSum() {
        // = 20000 + 4000 + 1000 (last iteration), NOT 75000 + 9000 + 2500 = 86500 (the summed top level).
        assertEquals(listOf<Int?>(25000), pushes(result(multiIterTurn)))
    }

    @Test
    fun twoTurns_eachReadsItsOwnLastIteration_noArtificialJumpFromMisreading() {
        // turn 1 last-iter occupancy = 40000; turn 2 shrinks (e.g. a compaction) to 15000 — a LEGITIMATE
        // decrease read from iterations.last, never the top-level sums (which would be 60000 then 40000).
        val turn1 = """{"input_tokens":60000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":9,
            "iterations":[{"input_tokens":20000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},
                          {"input_tokens":40000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}"""
        val turn2 = """{"input_tokens":40000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":9,
            "iterations":[{"input_tokens":25000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},
                          {"input_tokens":15000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}"""
        assertEquals(listOf<Int?>(40000, 15000), pushes(result(turn1), result(turn2)))
    }

    @Test
    fun occupancy_neverExceedsTheContextWindow_clampedToTheNamedConstant() {
        // An absurd last-iteration occupancy is sanity-clamped to the real window (not the stale 200k, not Int.MAX).
        val huge = """{"input_tokens":0,"iterations":[{"input_tokens":5000000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}"""
        val fed = pushes(result(huge)).single()!!
        assertEquals(ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS.toInt(), fed, "clamped to the window")
        assertTrue(fed <= ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS, "never exceeds the context window")
        assertEquals(1_000_000, ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS.toInt(), "window is the Auftraggeber's 1M, not 200k")
    }

    @Test
    fun connectorB_limited_stillPushesNull_notTheIterationCount() =
        assertEquals(listOf<Int?>(null), pushes(result(multiIterTurn), resolve = { caps(CapabilityStatus.LIMITED) }))

    @Test
    fun bander_bandsOnLastIterationOccupancy_notTheTurnSum_soBandAndNumberAgree() {
        // Same-root-cause visual band (CONTEXT_USAGE on /ws/events + /api/events): the top-level sum = 800k
        // (80%% of the 1M window → would FALSELY cross the compact threshold), while the last iteration = 250k
        // (25%% → a single band-20 step, no compact). The bander must read the last iteration, matching the number.
        val turn = """{"input_tokens":800000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":9,
            "iterations":[{"input_tokens":550000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},
                          {"input_tokens":250000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}]}"""
        val ctx = EventProjector(ContextUsageBander(), projectId = "t", capabilities = { caps(CapabilityStatus.AVAILABLE) })
            .project("backend", "s", "c", result(turn))
            .filter { it.type == EventType.CONTEXT_USAGE }
        assertEquals(1, ctx.size, "one band-20 step from the last iteration — NOT band-80 + compact from the 800k sum")
        assertEquals("band", ctx.single().detail["reason"]!!.jsonPrimitive.content, "no false compact crossing")
        assertEquals(250000L, ctx.single().detail["contextTokens"]!!.jsonPrimitive.long, "banded on the last iteration, not the sum")
    }

    // ---- the PROD wiring seam (BootOrchestrator builds the bander from EventsConfig, NOT the default ctor) ----

    @Test
    fun prodWindow_isSingleSourced_eventsConfigDefaultEqualsTheBanderConstant() {
        // The bug the first bander tooth missed: the title-bar clamp used DEFAULT_CONTEXT_WINDOW_TOKENS while the
        // PROD bander used EventsConfig.contextWindowTokens (a stale 200k second source). Now ONE constant.
        assertEquals(ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS, EventsConfig().contextWindowTokens,
            "prod bander window (EventsConfig default) and the title-bar clamp must share ONE constant")
        assertEquals(1_000_000L, EventsConfig().contextWindowTokens, "the real 1M window, not the stale 200k")
    }

    @Test
    fun prodWiredBander_250kTurn_bandsConsistentWith1MTitleBar_noFalseCompact() {
        // Build the bander EXACTLY as BootOrchestrator does — from EventsConfig — the seam the default-ctor tooth
        // skipped. 250k = 25%% of the 1M prod window → a band step, NO compact. (At the stale 200k, 250k > 100%%
        // → a false compact, diverging from the 250k title-bar number.)
        val bander = ContextUsageBander(contextWindowTokens = EventsConfig().contextWindowTokens)
        val ctx = EventProjector(bander, projectId = "t", capabilities = { caps(CapabilityStatus.AVAILABLE) })
            .project("backend", "s", "c", result("""{"input_tokens":250000,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}"""))
            .filter { it.type == EventType.CONTEXT_USAGE }
        assertTrue(ctx.none { it.detail["reason"]!!.jsonPrimitive.content == "compact" },
            "the PROD-wired bander must not falsely compact a 250k turn — consistent with the 1M title-bar clamp")
    }

    // ---- the wire-tolerant capsule directly (the stable seam QA's teeth also target) ----

    @Test
    fun capsule_multiIteration_returnsLastIterationOccupancy_notTopLevelSum() =
        assertEquals(25000L, UsageSnapshot.contextTokensFromUsage(usage(multiIterTurn)))

    @Test
    fun capsule_singleOrNoIterations_fallsBackToTopLevel_degraded() {
        // no iterations[] (single-iteration or older/edge CLI) → the top-level occupancy (input+cache_read+cache_creation).
        val bare = """{"input_tokens":12000,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":9999}"""
        assertEquals(15500L, UsageSnapshot.contextTokensFromUsage(usage(bare)))
    }

    @Test
    fun capsule_nullOrEmptyIterations_handledSafely() {
        assertNull(UsageSnapshot.contextTokensFromUsage(null))
        // empty iterations[] → fall back to the top-level occupancy (never throws).
        assertEquals(7L, UsageSnapshot.contextTokensFromUsage(usage("""{"input_tokens":7,"iterations":[]}""")))
    }
}
