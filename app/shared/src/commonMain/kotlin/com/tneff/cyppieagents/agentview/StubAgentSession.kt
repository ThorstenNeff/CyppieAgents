package com.tneff.cyppieagents.agentview

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Stand-in [AgentSession] for ungated development (CYP-6): no backend, no `:core` DTOs. It plays a
 * scripted scenario that exercises every renderer path — streaming assistant text, a tool call that
 * transitions RUNNING → OK, a marked result, an error result, and a notice — then echoes any human
 * turn as a fresh streamed reply.
 *
 * Replaced by the real Hub-WS-backed session once the CYP-5 spike freezes the event shape; the
 * renderer above this seam does not change.
 */
class StubAgentSession : AgentSession {

    private val human = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val events: Flow<AgentEvent> = flow {
        emit(AgentEvent.Notice("sys-1", "Session gestartet · Agent: backend"))

        // Streaming assistant text — three deltas under one id.
        emit(AgentEvent.AssistantText("a-1", "Ich sehe mir ", complete = false))
        delay(STEP)
        emit(AgentEvent.AssistantText("a-1", "die Build-Konfiguration ", complete = false))
        delay(STEP)
        emit(AgentEvent.AssistantText("a-1", "an.", complete = true))
        delay(STEP)

        // Tool call: RUNNING then resolved OK under the same id.
        emit(AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.RUNNING))
        delay(STEP)
        emit(AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.OK))
        emit(AgentEvent.Result("r-1", "42 Zeilen gelesen", isError = false))
        delay(STEP)

        // A failing tool call to drive the error path.
        emit(AgentEvent.ToolCall("t-2", "run_tests", ":app:shared:jvmTest", ToolStatus.RUNNING))
        delay(STEP)
        emit(AgentEvent.ToolCall("t-2", "run_tests", ":app:shared:jvmTest", ToolStatus.ERROR))
        emit(AgentEvent.Result("r-2", "1 Test fehlgeschlagen", isError = true))

        // Echo loop: each human turn becomes a streamed assistant reply.
        var turn = 0
        human.collect { msg ->
            turn++
            val id = "echo-$turn"
            emit(AgentEvent.AssistantText(id, "Verstanden: \"", complete = false))
            delay(STEP)
            emit(AgentEvent.AssistantText(id, msg, complete = false))
            delay(STEP)
            emit(AgentEvent.AssistantText(id, "\"", complete = true))
        }
    }

    override fun sendMessage(text: String) {
        human.tryEmit(text)
    }

    private companion object {
        const val STEP = 250L
    }
}
