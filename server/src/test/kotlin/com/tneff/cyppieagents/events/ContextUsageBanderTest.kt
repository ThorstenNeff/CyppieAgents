package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * ST2 (CYP-36) `context.usage` banding: a synthetic usage sequence persists an event ONLY on an
 * upward band crossing and on the compact threshold; the band width is configurable; fill state is
 * per-agent. Pure-logic assertions plus an end-to-end persistence check through the recorder→sink.
 */
class ContextUsageBanderTest {

    private val window = 200_000L
    private fun bander(bandPct: Int = 10, compactPct: Int = 75) =
        ContextUsageBander(contextWindowTokens = window, bandPctWidth = bandPct, compactPct = compactPct)

    /** Build a snapshot whose contextTokens equal [pct]% of the window. */
    private fun snap(pct: Double) = UsageSnapshot(inputTokens = (pct * window / 100.0).toLong(), outputTokens = 100)

    private fun EventDraft.tag(): Pair<String, Int> =
        detail["reason"]!!.jsonPrimitive.content to detail["bandPct"]!!.jsonPrimitive.int

    @Test
    fun emitsOnlyOnUpwardBandCrossing_andCompactThreshold() {
        val b = bander()
        val seq = listOf(5.0, 12.0, 19.0, 23.0, 47.0, 47.0, 9.0, 23.0, 78.0)
        val tags = seq.flatMap { b.onUsage("backend", "team", snap(it)) }.map { it.tag() }
        assertEquals(
            listOf(
                "band" to 10,  // 12% → enters band 10
                "band" to 20,  // 23% → band 20
                "band" to 40,  // 47% → band 40
                // 47% again, then 9% (shrinks, resets, no event)
                "band" to 20,  // 23% → re-crosses band 20 after the drop
                "band" to 70,  // 78% → band 70
                "compact" to 75, // 78% ≥ 75 → compact threshold
            ),
            tags,
        )
    }

    @Test
    fun bandWidthIsConfigurable() {
        val seq = listOf(5.0, 12.0, 19.0, 23.0, 47.0)
        val wide = bander(bandPct = 20)
        val wideTags = seq.flatMap { wide.onUsage("a", "t", snap(it)) }.map { it.tag() }
        // With 20% bands, 12% and 19% do NOT cross a band (band 0); 23%→20, 47%→40.
        assertEquals(listOf("band" to 20, "band" to 40), wideTags)

        val narrow = bander(bandPct = 10)
        val narrowTags = seq.flatMap { narrow.onUsage("a", "t", snap(it)) }.map { it.tag() }
        // With 10% bands, 12% already crosses band 10 → different behavior.
        assertEquals(listOf("band" to 10, "band" to 20, "band" to 40), narrowTags)
    }

    @Test
    fun compactThreshold_emitsOncePerCrossing_andReArmsAfterDrop() {
        val b = bander(bandPct = 10, compactPct = 75)
        assertEquals(listOf("band" to 70, "compact" to 75), b.onUsage("a", "t", snap(78.0)).map { it.tag() })
        assertEquals(listOf("band" to 80), b.onUsage("a", "t", snap(80.0)).map { it.tag() }) // still above → no 2nd compact
        assertEquals(emptyList(), b.onUsage("a", "t", snap(60.0)).map { it.tag() }) // drop below → re-arm, no event
        // climb back through 75 → compact fires again
        assertEquals(listOf("band" to 70, "compact" to 75), b.onUsage("a", "t", snap(76.0)).map { it.tag() })
    }

    @Test
    fun compactSeverityIsWarn_bandIsInfo() {
        val b = bander()
        val drafts = b.onUsage("a", "t", snap(78.0))
        assertEquals(Severity.INFO, drafts.first { it.detail["reason"]!!.jsonPrimitive.content == "band" }.severity)
        assertEquals(Severity.WARN, drafts.first { it.detail["reason"]!!.jsonPrimitive.content == "compact" }.severity)
    }

    @Test
    fun fillStateIsPerAgent() {
        val b = bander()
        assertEquals(listOf("band" to 10), b.onUsage("A", "t", snap(12.0)).map { it.tag() })
        assertEquals(emptyList(), b.onUsage("B", "t", snap(5.0)).map { it.tag() }) // B independent, no crossing
        assertEquals(emptyList(), b.onUsage("A", "t", snap(15.0)).map { it.tag() }) // A still band 10
        assertEquals(listOf("band" to 10), b.onUsage("B", "t", snap(12.0)).map { it.tag() }) // B crosses its own band 10
    }

    @Test
    fun reset_dropsAgentState_soNextCrossingReEmits() {
        val b = bander()
        assertEquals(listOf("band" to 10), b.onUsage("a", "t", snap(12.0)).map { it.tag() })
        b.reset("a")
        assertEquals(listOf("band" to 10), b.onUsage("a", "t", snap(12.0)).map { it.tag() }) // re-emits after reset
    }

    @Test
    fun persistsOnlyCrossings_endToEnd_throughRecorderAndSink() = runBlocking {
        val sink = InMemoryEventSink(SystemTimeSource())
        val recorder = EventRecorder(sink, this, capacity = 256, batchSize = 16)
        recorder.start()
        val b = bander()
        // 9 turns, but only 4 of them cross a band/threshold (12→b10, 23→b20, 47→b40, 78→b70+compact).
        listOf(5.0, 12.0, 19.0, 23.0, 47.0, 47.0, 60.0, 70.0, 78.0).forEach { pct ->
            b.onUsage("backend", "team", snap(pct)).forEach { recorder.record(it) }
        }
        recorder.stop()
        val usage = sink.query(EventFilter(type = EventType.CONTEXT_USAGE), Page(limit = 100)).events
        // band10, band20, band40, band60(70%→band70? 70→band70), band70, compact — count the crossings.
        // 12→b10, 23→b20, 47→b40, 60→b60, 70→b70, 78→ (band 70 already) compact only.
        val tags = usage.map { it.detail["reason"]!!.jsonPrimitive.content to it.detail["bandPct"]!!.jsonPrimitive.int }
        assertEquals(
            listOf(
                "band" to 10, "band" to 20, "band" to 40, "band" to 60, "band" to 70, "compact" to 75,
            ),
            tags,
        )
        assertTrue(usage.all { it.agentId == "backend" })
        // Non-crossing turns (5,19,47-again) persisted nothing extra.
        assertEquals(6, usage.size)
    }
}
