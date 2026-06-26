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
            deriveStatus(listOf(AgentEvent.AssistantText("a", "teil", complete = false))),
        )
    }

    @Test
    fun completeAssistant_isIdle() {
        assertEquals(
            AgentStatus.IDLE,
            deriveStatus(listOf(AgentEvent.AssistantText("a", "fertig", complete = true))),
        )
    }

    @Test
    fun runningTool_isRunning() {
        assertEquals(
            AgentStatus.RUNNING,
            deriveStatus(listOf(AgentEvent.ToolCall("t", "Bash", "x", ToolStatus.RUNNING))),
        )
    }

    @Test
    fun errorTool_isError() {
        assertEquals(
            AgentStatus.ERROR,
            deriveStatus(listOf(AgentEvent.ToolCall("t", "Bash", "x", ToolStatus.ERROR))),
        )
    }

    @Test
    fun successResult_isIdle_notWaiting() {
        // The honesty core: a finished successful turn is IDLE, never guessed as WAITING_FOR_INPUT.
        assertEquals(
            AgentStatus.IDLE,
            deriveStatus(listOf(AgentEvent.Result("r", "Turn abgeschlossen", isError = false))),
        )
    }

    @Test
    fun errorResult_isError() {
        assertEquals(
            AgentStatus.ERROR,
            deriveStatus(listOf(AgentEvent.Result("r", "fehlgeschlagen", isError = true))),
        )
    }

    @Test
    fun statusReflectsLatestEvent() {
        val transcript = listOf(
            AgentEvent.ToolCall("t", "Bash", "x", ToolStatus.RUNNING),
            AgentEvent.Result("r", "ok", isError = false),
        )
        assertEquals(AgentStatus.IDLE, deriveStatus(transcript))
    }
}
