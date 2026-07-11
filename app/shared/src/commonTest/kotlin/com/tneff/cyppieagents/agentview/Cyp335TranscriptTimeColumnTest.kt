package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-335 (QA) — the property the operator actually reads off the screen: **the time column never runs
 * backwards.**
 *
 * The existing tests each pin one half of this. `TranscriptFoldingTest.parallelToolCalls_…` proves the two
 * `ToolCall` rows keep their start times, but it hand-builds [AgentEvent]s and omits the `Result` rows.
 * `StreamJsonMapperTest.toolResultFanOut_datesTheTwoRowsDifferently` proves one wire event fans out into two
 * rows with *different* stamps, but only inspects that one fan-out in isolation.
 *
 * Neither one answers the question those two answers raise together: if the resolved `ToolCall` is dated by its
 * start and the `Result` beside it is dated by its arrival, does the **rendered column** still read top-to-bottom
 * in non-decreasing time? This test drives the real `StreamJsonMapper` + [foldEvent] pipeline with **parallel,
 * out-of-order-completing** tool calls and asserts the whole column at once.
 */
class Cyp335TranscriptTimeColumnTest {

    private fun toolUse(id: String, uuid: String) = AssistantEvent(
        message = AgentMessage(
            id = "m-$id",
            stopReason = "tool_use",
            content = listOf(ToolUseBlock(id = id, name = "Bash", input = buildJsonObject { put("command", "x") })),
        ),
        uuid = uuid,
    )

    private fun toolResult(id: String, uuid: String) = UserEvent(
        message = AgentMessage(
            content = listOf(ToolResultBlock(toolUseId = id, content = JsonPrimitive("done"), isError = false)),
        ),
        uuid = uuid,
    )

    /** Drives mapper + fold with an explicit server stamp per wire event — exactly what `MappingAgentSession` does. */
    private fun pipeline(stamped: List<Pair<StreamJsonEvent, Long>>): List<AgentEvent> {
        val mapper = StreamJsonMapper(readyNoticeText = "READY")
        return foldEvents(stamped.flatMap { (event, tsMs) -> mapper.map(event, tsMs) })
    }

    @Test
    fun parallelToolCalls_completingOutOfOrder_leaveTheTimeColumnMonotonic() {
        // A starts 14:03, B starts 14:04. B finishes 14:05, A only at 14:07 — Claude Code fires tools in
        // parallel, so the *completion* order is genuinely reversed against the *start* order.
        val h = 3_600_000L
        val m = 60_000L
        val at1403 = 14 * h + 3 * m
        val at1404 = 14 * h + 4 * m
        val at1405 = 14 * h + 5 * m
        val at1407 = 14 * h + 7 * m

        val rows = pipeline(
            listOf(
                toolUse("toolu_a", "ua") to at1403,
                toolUse("toolu_b", "ub") to at1404,
                toolResult("toolu_b", "rb") to at1405, // fan-out: resolved ToolCall b + Result b
                toolResult("toolu_a", "ra") to at1407, // fan-out: resolved ToolCall a + Result a
            )
        )

        // Four rows, in first-appearance order: the two tool calls, then each result as it arrived.
        assertEquals(4, rows.size, "two tool calls + two results")

        val callA = rows[0] as AgentEvent.ToolCall
        val callB = rows[1] as AgentEvent.ToolCall
        assertEquals(ToolStatus.OK, callA.status, "A resolved in place")
        assertEquals(ToolStatus.OK, callB.status, "B resolved in place")

        // Each tool-call row is dated by its START, not by the result that resolved it minutes later.
        assertEquals(at1403, callA.tsMs, "the slow tool call keeps its 14:03 start, not its 14:07 finish")
        assertEquals(at1404, callB.tsMs, "the fast tool call keeps its 14:04 start")

        // The result rows carry the ARRIVAL time — the end time is not lost, it just lives on its own row.
        assertEquals(at1405, (rows[2] as AgentEvent.Result).tsMs, "B's result is dated when it arrived")
        assertEquals(at1407, (rows[3] as AgentEvent.Result).tsMs, "A's result is dated when it arrived")

        // The property the operator sees: reading the gutter downwards never goes back in time.
        val column = rows.map { it.tsMs }
        assertEquals(listOf(at1403, at1404, at1405, at1407), column)
        assertTrue(
            column.zipWithNext().all { (a, b) -> a <= b },
            "the rendered time column must never run backwards, got ${column.map { formatHhMm(it, 0L) }}",
        )
    }

    @Test
    fun renderedColumn_readsAsWallClock() {
        // The same scenario, expressed the way a human reads it — guards the format as well as the ordering.
        val h = 3_600_000L
        val m = 60_000L
        val rows = pipeline(
            listOf(
                toolUse("toolu_a", "ua") to 14 * h + 3 * m,
                toolUse("toolu_b", "ub") to 14 * h + 4 * m,
                toolResult("toolu_b", "rb") to 14 * h + 5 * m,
                toolResult("toolu_a", "ra") to 14 * h + 7 * m,
            )
        )
        assertEquals(
            listOf("14:03", "14:04", "14:05", "14:07"),
            rows.map { formatHhMm(it.tsMs, offsetMs = 0L) },
            "start-dated tool calls, arrival-dated results, monotonic column",
        )
    }
}
