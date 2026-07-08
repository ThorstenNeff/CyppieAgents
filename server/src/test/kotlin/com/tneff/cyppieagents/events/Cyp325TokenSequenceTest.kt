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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-325 (QA money tooth) — the multi-turn EVENT-SEQUENCE the single-result teeth don't cover. The title-bar
 * display must equal the LAST turn's last-iteration occupancy at every step, NEVER the turn-sum, and a
 * legitimate post-compaction DECREASE must be shown (not suppressed as a "backward jump"). Built directly on
 * the encapsulated parse seam [UsageSnapshot.contextTokensFromUsage] (survives the wire swap) and the real
 * [EventProjector] → onContextTokens path. The band is parametrised against the NAMED constant
 * [ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS] — never a 200k/1M hard-code.
 *
 * Mutations that MUST red these (QA self-verifies):
 *  - capsule `iterations.last` → top-level sum → the 25k / [10k,40k,25k] assertions red (they'd show the sum);
 *  - window constant → 200k                    → the "≤ window" clamp assertion reds.
 */
class Cyp325TokenSequenceTest {

    private fun caps(usage: CapabilityStatus = CapabilityStatus.AVAILABLE) = Capabilities(
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

    /** Project each event, collecting the values pushed to the onContextTokens sink (structuredUsage = ENABLED). */
    private fun pushes(vararg events: StreamJsonEvent): List<Int?> {
        val out = ArrayList<Int?>()
        val p = EventProjector(ContextUsageBander(), projectId = "t", capabilities = { caps() }, onContextTokens = { _, t -> out.add(t) })
        events.forEach { p.project("backend", "s", "c", it) }
        return out
    }

    /** One turn whose server-side tool-use iterations carry the given per-iteration input sizes; the top-level
     *  `input_tokens` is the CLI's running SUM across them (the CYP-325 overcount source). Cache fields 0 so the
     *  occupancy = the input size, keeping the 10k/40k/25k arithmetic legible. */
    private fun turnWithIters(vararg iterInputs: Int, topLevelSum: Int): String {
        val iters = iterInputs.joinToString(",") {
            """{"input_tokens":$it,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":50}"""
        }
        return """{"input_tokens":$topLevelSum,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,"output_tokens":999,"iterations":[$iters]}"""
    }

    @Test
    fun singleTurn_iterations10k40k25k_displaysLastIteration25k_notSum() {
        // iterations climb 10k→40k then settle at 25k → current context = last iteration (25k); the top-level
        // running sum (75k) must NOT surface. Proven on BOTH the capsule and the real projector feed.
        val t = turnWithIters(10_000, 40_000, 25_000, topLevelSum = 75_000)
        assertEquals(25_000L, UsageSnapshot.contextTokensFromUsage(usage(t)), "capsule must read iterations.last (25k), not the 75k sum")
        assertEquals(listOf<Int?>(25_000), pushes(result(t)), "display feed must be 25k, not the 75k turn-sum")
    }

    @Test
    fun threeTurnSequence_displayTracksEachTurnsLastIteration_decreaseIsShown() {
        // Turn A last-iter 10k, Turn B last-iter 40k, Turn C last-iter 25k (a legitimate compaction decrease).
        // The display sequence must be exactly [10k, 40k, 25k] — following the last turn, NEVER a running sum
        // (which would be [10k, 50k, 75k]) and NEVER suppressing the legitimate 40k→25k decrease.
        val a = turnWithIters(8_000, 10_000, topLevelSum = 18_000)
        val b = turnWithIters(30_000, 40_000, topLevelSum = 70_000)
        val c = turnWithIters(28_000, 25_000, topLevelSum = 53_000)
        assertEquals(listOf<Int?>(10_000, 40_000, 25_000), pushes(result(a), result(b), result(c)))
    }

    @Test
    fun sequenceValues_stayWithinBand_zeroToWindow_parametrisedAgainstConstant() {
        val window = ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS
        // Literal anchor (belt-and-suspenders): the clamp assertions below track `window`, so they alone can't
        // catch the CONSTANT itself regressing (200k→1M). Pin the Auftraggeber's required window value here so a
        // window→200k regression reds THIS tooth too, not only dev's occupancy pin.
        assertEquals(1_000_000L, window, "context window must be the Auftraggeber's 1M, not the stale 200k")
        val a = turnWithIters(10_000, topLevelSum = 10_000)
        val big = turnWithIters(5_000_000, topLevelSum = 5_000_000) // absurd last-iter → sanity-clamp to the window
        val fed = pushes(result(a), result(big))
        fed.forEach { v ->
            assertTrue(v != null && v >= 0 && v <= window, "each displayed value must sit in 0..window ($window) — was $v")
        }
        assertEquals(window.toInt(), fed[1], "over-window occupancy clamps to the named window constant, not 200k/Int.MAX")
    }

    /**
     * CYP-325 re-verify (the first-pass NO-GO's lesson): my earlier teeth default-constructed `ContextUsageBander()`
     * (the 1M const), which MASKED the PROD divergence where the bander window was injected from
     * `EventsConfig.contextWindowTokens` (a stale 200k SECOND source) while the title-bar clamp read the const.
     * This tooth builds the bander EXACTLY as `BootOrchestrator` does — from `EventsConfig` — and cross-asserts that
     * the title-bar NUMBER (onContextTokens, clamped to the const) AND the BAND (the EventsConfig-wired bander) BOTH
     * reflect the ONE 1M window for a single 250k turn: number = 250k (25%, unclamped), band = NO compact. If the two
     * windows ever diverge again (EventsConfig default → 200k), the number stays 250k but the band false-compacts →
     * this reds. Stronger than a band-only check: it pins number↔band CONSISTENCY at the prod seam.
     */
    @Test
    fun prodWiredSeam_250kTurn_numberAndBandBothReflect1M_consistent() {
        assertEquals(
            ContextUsageBander.DEFAULT_CONTEXT_WINDOW_TOKENS, EventsConfig().contextWindowTokens,
            "prod bander window (EventsConfig default) and the title-bar clamp const must be ONE source",
        )
        val numbers = ArrayList<Int?>()
        val bander = ContextUsageBander(contextWindowTokens = EventsConfig().contextWindowTokens) // as BootOrchestrator wires it
        val drafts = EventProjector(bander, projectId = "t", capabilities = { caps() }, onContextTokens = { _, t -> numbers.add(t) })
            .project("backend", "s", "c", result(turnWithIters(250_000, topLevelSum = 250_000)))
        // Title-bar number: 250k is 25% of the 1M window → shown unclamped.
        assertEquals(listOf<Int?>(250_000), numbers, "title-bar number reflects the 1M window (250k, unclamped)")
        // Band from the PROD-wired bander: 25% → a band step, NEVER compact. Consistency with the number above.
        assertTrue(
            drafts.filter { it.type == EventType.CONTEXT_USAGE }
                .none { it.detail["reason"]!!.jsonPrimitive.content == "compact" },
            "the PROD-wired band must not compact a 250k turn — number and band must agree on the 1M window",
        )
    }
}
