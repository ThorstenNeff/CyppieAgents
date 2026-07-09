package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptFoldingTest {

    @Test
    fun assistantDeltas_sameId_concatenateIntoOneItem() {
        val folded = foldEvents(
            listOf(
                AgentEvent.AssistantText("a-1", "Hallo ", complete = false, tsMs = 0L),
                AgentEvent.AssistantText("a-1", "Welt", complete = false, tsMs = 0L),
                AgentEvent.AssistantText("a-1", "!", complete = true, tsMs = 0L),
            )
        )
        assertEquals(1, folded.size, "deltas with one id must fold into a single item")
        val item = folded.single() as AgentEvent.AssistantText
        assertEquals("Hallo Welt!", item.text)
        assertTrue(item.complete, "last delta's complete flag wins")
    }

    @Test
    fun assistantText_differentIds_areSeparateItems() {
        val folded = foldEvents(
            listOf(
                AgentEvent.AssistantText("a-1", "erste", complete = true, tsMs = 0L),
                AgentEvent.AssistantText("a-2", "zweite", complete = false, tsMs = 0L),
            )
        )
        assertEquals(2, folded.size)
    }

    @Test
    fun toolCall_sameId_updatesInPlaceKeepingPosition() {
        val folded = foldEvents(
            listOf(
                AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.RUNNING, tsMs = 0L),
                AgentEvent.Notice("n-1", "zwischendrin", tsMs = 0L),
                AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.OK, tsMs = 0L),
            )
        )
        assertEquals(2, folded.size, "the re-emitted tool call must update, not append")
        val tool = folded[0] as AgentEvent.ToolCall
        assertEquals(ToolStatus.OK, tool.status, "status updated in place")
        assertTrue(folded[1] is AgentEvent.Notice, "the tool call kept its original position")
    }

    @Test
    fun resultAndNotice_areDedupedById() {
        val folded = foldEvents(
            listOf(
                AgentEvent.Result("r-1", "ok", isError = false, tsMs = 0L),
                AgentEvent.Result("r-1", "ok", isError = false, tsMs = 0L), // reconnect replay
                AgentEvent.Notice("n-1", "hi", tsMs = 0L),
                AgentEvent.Notice("n-1", "hi", tsMs = 0L),                  // reconnect replay
            )
        )
        assertEquals(2, folded.size, "replayed terminal events must not duplicate")
    }

    @Test
    fun scriptedScenario_ordersAndMarksCorrectly() {
        val folded = foldEvents(
            listOf(
                AgentEvent.Notice("sys-1", "Session gestartet", tsMs = 0L),
                AgentEvent.AssistantText("a-1", "Ich prüfe ", complete = false, tsMs = 0L),
                AgentEvent.AssistantText("a-1", "den Build.", complete = true, tsMs = 0L),
                AgentEvent.ToolCall("t-1", "run_tests", ":app:shared:jvmTest", ToolStatus.RUNNING, tsMs = 0L),
                AgentEvent.ToolCall("t-1", "run_tests", ":app:shared:jvmTest", ToolStatus.ERROR, tsMs = 0L),
                AgentEvent.Result("r-1", "1 Test fehlgeschlagen", isError = true, tsMs = 0L),
            )
        )
        assertEquals(4, folded.size)
        assertTrue(folded[0] is AgentEvent.Notice)
        assertEquals("Ich prüfe den Build.", (folded[1] as AgentEvent.AssistantText).text)
        assertEquals(ToolStatus.ERROR, (folded[2] as AgentEvent.ToolCall).status)
        val result = folded[3] as AgentEvent.Result
        assertTrue(result.isError, "failing result is marked as error")
    }

    @Test
    fun emptyStream_yieldsEmptyTranscript() {
        assertTrue(foldEvents(emptyList()).isEmpty())
    }

    @Test
    fun assistantText_notYetComplete_keepsStreamingFlag() {
        val folded = foldEvents(
            listOf(AgentEvent.AssistantText("a-1", "teil", complete = false, tsMs = 0L))
        )
        assertFalse((folded.single() as AgentEvent.AssistantText).complete)
    }

    // --- CYP-335: a row is dated by its BIRTH, never by its last update ---

    @Test
    fun assistantDeltas_keepFirstDeltaTimestamp() {
        // The turn began at 1_000; later deltas of the same turn must not re-date the row.
        val folded = foldEvents(
            listOf(
                AgentEvent.AssistantText("a-1", "Ich sehe mir ", complete = false, tsMs = 1_000L),
                AgentEvent.AssistantText("a-1", "den Build ", complete = false, tsMs = 5_000L),
                AgentEvent.AssistantText("a-1", "an.", complete = true, tsMs = 9_000L),
            )
        )
        assertEquals(1_000L, folded.single().tsMs, "a growing assistant row keeps its FIRST delta's timestamp")
    }

    @Test
    fun toolCall_runningToOk_keepsStartTimestamp() {
        // The naive `it[idx] = event` re-dates the row to the moment the tool FINISHED. The end time is not
        // lost — it lives in the Result row — but the tool-call row must say when the tool was invoked.
        val folded = foldEvents(
            listOf(
                AgentEvent.ToolCall("t-1", "read_file", "b.kts", ToolStatus.RUNNING, tsMs = 1_000L),
                AgentEvent.ToolCall("t-1", "read_file", "b.kts", ToolStatus.OK, tsMs = 90_000L),
            )
        )
        val tool = folded.single() as AgentEvent.ToolCall
        assertEquals(ToolStatus.OK, tool.status, "the payload is the resolved one")
        assertEquals(1_000L, tool.tsMs, "but the row keeps the tool call's START timestamp")
    }

    @Test
    fun toolCall_runningToError_keepsStartTimestamp() {
        val folded = foldEvents(
            listOf(
                AgentEvent.ToolCall("t-2", "run_tests", ":x", ToolStatus.RUNNING, tsMs = 2_000L),
                AgentEvent.ToolCall("t-2", "run_tests", ":x", ToolStatus.ERROR, tsMs = 80_000L),
            )
        )
        val tool = folded.single() as AgentEvent.ToolCall
        assertEquals(ToolStatus.ERROR, tool.status)
        assertEquals(2_000L, tool.tsMs, "a failing tool call is dated by its start too")
    }

    @Test
    fun parallelToolCalls_timeColumnDoesNotRunBackwards() {
        // A starts first, B finishes first. Claude Code fires tool calls in parallel and foldEvent updates the
        // row in place, so the list stays in first-appearance order. Start-dating reads 1_000 then 2_000;
        // end-dating would read 9_000 then 3_000 — a clock running backwards down the transcript.
        val folded = foldEvents(
            listOf(
                AgentEvent.ToolCall("a", "slow", "", ToolStatus.RUNNING, tsMs = 1_000L),
                AgentEvent.ToolCall("b", "fast", "", ToolStatus.RUNNING, tsMs = 2_000L),
                AgentEvent.ToolCall("b", "fast", "", ToolStatus.OK, tsMs = 3_000L),
                AgentEvent.ToolCall("a", "slow", "", ToolStatus.OK, tsMs = 9_000L),
            )
        )
        assertEquals(listOf(1_000L, 2_000L), folded.map { it.tsMs }, "rows keep start order AND start times")
    }

    @Test
    fun replayedTerminalEvent_doesNotRedateTheSurvivingRow() {
        // A reconnect replays the same Result under the same id; the deduped row keeps its original time.
        val folded = foldEvents(
            listOf(
                AgentEvent.Result("r-1", "ok", isError = false, tsMs = 1_000L),
                AgentEvent.Result("r-1", "ok", isError = false, tsMs = 50_000L),
            )
        )
        assertEquals(1_000L, folded.single().tsMs, "the replayed duplicate must not overwrite the original time")
    }
}
