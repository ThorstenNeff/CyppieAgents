package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentStatusTest {

    @Test
    fun empty_isIdle() {
        assertEquals(AgentStatus.IDLE, deriveStatus(emptyList()))
    }

    @Test
    fun streamingAssistant_isRunning() {
        assertEquals(
            AgentStatus.RUNNING,
            deriveStatus(listOf(AgentEvent.AssistantText("a", "teil", complete = false, tsMs = 0L))),
        )
    }

    @Test
    fun completeAssistant_isIdle() {
        assertEquals(
            AgentStatus.IDLE,
            deriveStatus(listOf(AgentEvent.AssistantText("a", "fertig", complete = true, tsMs = 0L))),
        )
    }

    @Test
    fun runningTool_isRunning() {
        assertEquals(
            AgentStatus.RUNNING,
            deriveStatus(listOf(AgentEvent.ToolCall("t", "Bash", "x", ToolStatus.RUNNING, tsMs = 0L))),
        )
    }

    @Test
    fun errorTool_isError() {
        assertEquals(
            AgentStatus.ERROR,
            deriveStatus(listOf(AgentEvent.ToolCall("t", "Bash", "x", ToolStatus.ERROR, tsMs = 0L))),
        )
    }

    @Test
    fun successResult_isIdle_notWaiting() {
        // The honesty core: a finished successful turn is IDLE, never guessed as WAITING_FOR_INPUT.
        assertEquals(
            AgentStatus.IDLE,
            deriveStatus(listOf(AgentEvent.Result("r", "Turn abgeschlossen", isError = false, tsMs = 0L))),
        )
    }

    @Test
    fun errorResult_isError() {
        assertEquals(
            AgentStatus.ERROR,
            deriveStatus(listOf(AgentEvent.Result("r", "fehlgeschlagen", isError = true, tsMs = 0L))),
        )
    }

    @Test
    fun statusReflectsLatestEvent() {
        val transcript = listOf(
            AgentEvent.ToolCall("t", "Bash", "x", ToolStatus.RUNNING, tsMs = 0L),
            AgentEvent.Result("r", "ok", isError = false, tsMs = 0L),
        )
        assertEquals(AgentStatus.IDLE, deriveStatus(transcript))
    }
}
