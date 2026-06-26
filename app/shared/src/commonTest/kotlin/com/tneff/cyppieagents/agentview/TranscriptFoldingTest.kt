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
                AgentEvent.AssistantText("a-1", "Hallo ", complete = false),
                AgentEvent.AssistantText("a-1", "Welt", complete = false),
                AgentEvent.AssistantText("a-1", "!", complete = true),
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
                AgentEvent.AssistantText("a-1", "erste", complete = true),
                AgentEvent.AssistantText("a-2", "zweite", complete = false),
            )
        )
        assertEquals(2, folded.size)
    }

    @Test
    fun toolCall_sameId_updatesInPlaceKeepingPosition() {
        val folded = foldEvents(
            listOf(
                AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.RUNNING),
                AgentEvent.Notice("n-1", "zwischendrin"),
                AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.OK),
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
                AgentEvent.Result("r-1", "ok", isError = false),
                AgentEvent.Result("r-1", "ok", isError = false), // reconnect replay
                AgentEvent.Notice("n-1", "hi"),
                AgentEvent.Notice("n-1", "hi"),                  // reconnect replay
            )
        )
        assertEquals(2, folded.size, "replayed terminal events must not duplicate")
    }

    @Test
    fun scriptedScenario_ordersAndMarksCorrectly() {
        val folded = foldEvents(
            listOf(
                AgentEvent.Notice("sys-1", "Session gestartet"),
                AgentEvent.AssistantText("a-1", "Ich prüfe ", complete = false),
                AgentEvent.AssistantText("a-1", "den Build.", complete = true),
                AgentEvent.ToolCall("t-1", "run_tests", ":app:shared:jvmTest", ToolStatus.RUNNING),
                AgentEvent.ToolCall("t-1", "run_tests", ":app:shared:jvmTest", ToolStatus.ERROR),
                AgentEvent.Result("r-1", "1 Test fehlgeschlagen", isError = true),
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
            listOf(AgentEvent.AssistantText("a-1", "teil", complete = false))
        )
        assertFalse((folded.single() as AgentEvent.AssistantText).complete)
    }
}
