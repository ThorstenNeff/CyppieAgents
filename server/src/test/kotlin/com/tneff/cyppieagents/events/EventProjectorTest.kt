package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.connector.EventMasking
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.StreamJsonEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ST3 (CYP-37) projection contract, driven by the canonical corpus (`/streamjson/corpus.ndjson`).
 * Asserts the right event types come out AND — the structural Non-Goal — that **no content** reaches
 * `detail`. The corpus content fields all carry `LEAK_*` markers; the needle-absence guard proves none
 * survive masking + metadata-only projection. (The Tester's two-class adversarial needle is layered on
 * top per CYP-44 §4.)
 */
class EventProjectorTest {

    private fun corpusDrafts(): List<EventDraft> {
        val lines = javaClass.getResourceAsStream("/streamjson/corpus.ndjson")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val projector = EventProjector(ContextUsageBander(), projectId = "team-1")
        return lines.flatMap { line ->
            val masked = EventMasking.mask(CommJson.decodeFromString<StreamJsonEvent>(line))
            projector.project("backend", masked.sessionId, "corr-1", masked)
        }
    }

    @Test
    fun projectsExpectedTypes_fromRealShapes() {
        val byType = corpusDrafts().groupingBy { it.type }.eachCount()
        assertEquals(2, byType[EventType.TOOL_CALL], "two tool_use blocks")
        assertEquals(2, byType[EventType.TOOL_RESULT], "two tool_result blocks (ok + error)")
        assertEquals(2, byType[EventType.RESULT_FINAL], "success + error results")
        assertEquals(1, byType[EventType.ERROR_MODEL], "the error result classifies an error")
        assertEquals(1, byType[EventType.ERROR_RATELIMIT], "rate_limit_event")
        assertEquals(2, byType[EventType.CONTEXT_USAGE], "85% → band 80 crossing + compact threshold")
        // No turn.* here: those are connector-emitted lifecycle, not stream-projected.
        assertFalse(byType.containsKey(EventType.TURN_START))
    }

    @Test
    fun toolCallCarriesNameAndId_notInput() {
        val calls = corpusDrafts().filter { it.type == EventType.TOOL_CALL }
        assertEquals(setOf("Bash", "Read"), calls.map { it.detail["toolName"]!!.jsonPrimitive.content }.toSet())
        assertEquals(setOf("toolu_01", "toolu_02"), calls.map { it.detail["toolUseId"]!!.jsonPrimitive.content }.toSet())
        assertTrue(calls.all { "input" !in it.detail.keys && "command" !in it.detail.keys }, "tool input is never projected")
    }

    @Test
    fun toolResultCarriesIsError_notContent() {
        val results = corpusDrafts().filter { it.type == EventType.TOOL_RESULT }
        assertEquals(setOf(false, true), results.map { it.detail["isError"]!!.jsonPrimitive.content.toBoolean() }.toSet())
        assertTrue(results.all { "content" !in it.detail.keys }, "tool result content is never projected")
    }

    @Test
    fun correlationId_isCarried() {
        assertTrue(corpusDrafts().all { it.correlationId == "corr-1" })
    }

    @Test
    fun rateLimitProjectsRealCamelCaseSchema_excludesOverageStatusTrap() {
        // The REAL wire shape (CYP-59 live spike), not the old synthetic snake_case.
        val info = kotlinx.serialization.json.buildJsonObject {
            put("status", "blocked")            // the throttle discriminator
            put("resetsAt", 1782657000L)
            put("rateLimitType", "five_hour")
            put("overageStatus", "rejected")    // live "rejected" even when healthy — the trap
            put("isUsingOverage", false)
        }
        val event = com.tneff.cyppieagents.model.RateLimitEvent(rateLimitInfo = info, sessionId = "s")
        val draft = EventProjector(ContextUsageBander(), projectId = "t").project("backend", "s", "c", event).single()

        assertEquals(EventType.ERROR_RATELIMIT, draft.type)
        assertEquals("blocked", draft.detail["status"]!!.jsonPrimitive.content)
        assertTrue("resetsAt" in draft.detail.keys, "real camelCase field resetsAt must be projected")
        assertTrue("rateLimitType" in draft.detail.keys, "real camelCase field rateLimitType must be projected")
        assertFalse("overageStatus" in draft.detail.keys, "overageStatus (CYP-59 trap) must never be projected")
        assertFalse("reset_at" in draft.detail.keys, "synthetic snake_case is not the real schema")
    }

    @Test
    fun needleAbsence_noContentLeaksIntoDetail() {
        val drafts = corpusDrafts()
        assertTrue(drafts.isNotEmpty())
        for (d in drafts) {
            val rendered = d.detail.toString()
            assertFalse(rendered.contains("LEAK_"), "content leaked into ${d.type} detail: $rendered")
            // Also assert specific content classes never appear anywhere in the draft's metadata.
            assertFalse(rendered.contains("passwd"))
            assertFalse(rendered.contains("permission denied"))
        }
    }
}
