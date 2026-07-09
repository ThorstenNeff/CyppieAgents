package com.tneff.cyppieagents.agentview

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

/**
 * Stand-in [AgentSession] for ungated development (CYP-6): no backend, no `:core` DTOs. It plays a
 * scripted scenario that exercises every renderer path — streaming assistant text, a tool call that
 * transitions RUNNING → OK, a marked result, an error result, a notice and an injected system message —
 * then echoes any human turn as a fresh streamed reply.
 *
 * Replaced by the real Hub-WS-backed session once the CYP-5 spike freezes the event shape; the
 * renderer above this seam does not change.
 *
 * CYP-335: the real session dates its rows from the server's `StoredAgentEvent.tsMs`; the stub has no wire,
 * so it stamps [nowMs] as it emits. That makes the scripted rows carry *rising, real* clock times in the
 * dev/demo build — the timestamps you see there are honest, not placeholders.
 */
class StubAgentSession(
    /** Injected so a test can fake the clock; the demo build takes the real one. */
    private val nowMs: () -> Long = platformTranscriptClock()::nowMs,
) : AgentSession {

    private val human = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val events: Flow<AgentEvent> = flow {
        emit(AgentEvent.Notice("sys-1", "Session gestartet · Agent: backend", nowMs()))

        // Streaming assistant text — three deltas under one id. Only the first delta's stamp survives the fold.
        emit(AgentEvent.AssistantText("a-1", "Ich sehe mir ", complete = false, tsMs = nowMs()))
        delay(STEP)
        emit(AgentEvent.AssistantText("a-1", "die Build-Konfiguration ", complete = false, tsMs = nowMs()))
        delay(STEP)
        emit(AgentEvent.AssistantText("a-1", "an.", complete = true, tsMs = nowMs()))
        delay(STEP)

        // Tool call: RUNNING then resolved OK under the same id. The row keeps the RUNNING stamp.
        emit(AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.RUNNING, nowMs()))
        delay(STEP)
        emit(AgentEvent.ToolCall("t-1", "read_file", "build.gradle.kts", ToolStatus.OK, nowMs()))
        emit(AgentEvent.Result("r-1", "42 Zeilen gelesen", isError = false, tsMs = nowMs()))
        delay(STEP)

        // A failing tool call to drive the error path.
        emit(AgentEvent.ToolCall("t-2", "run_tests", ":app:shared:jvmTest", ToolStatus.RUNNING, nowMs()))
        delay(STEP)
        emit(AgentEvent.ToolCall("t-2", "run_tests", ":app:shared:jvmTest", ToolStatus.ERROR, nowMs()))
        emit(AgentEvent.Result("r-2", "1 Test fehlgeschlagen", isError = true, tsMs = nowMs()))
        delay(STEP)

        // CYP-335: appended so the scripted scenario really does cover EVERY renderer path (the KDoc claimed it
        // before this row existed) — the six-line-type timestamp check is then visible in the demo build without a
        // backend. Appended last, so no existing `agent.<id>.event.<index>` tag shifts.
        emit(AgentEvent.IncomingSystem("sys-inject-1", "/compact", nowMs()))

        // Echo loop: each human turn becomes a streamed assistant reply.
        var turn = 0
        human.collect { msg ->
            turn++
            val id = "echo-$turn"
            emit(AgentEvent.AssistantText(id, "Verstanden: \"", complete = false, tsMs = nowMs()))
            delay(STEP)
            emit(AgentEvent.AssistantText(id, msg, complete = false, tsMs = nowMs()))
            delay(STEP)
            emit(AgentEvent.AssistantText(id, "\"", complete = true, tsMs = nowMs()))
        }
    }

    override fun sendMessage(text: String) {
        human.tryEmit(text)
    }

    private companion object {
        const val STEP = 250L
    }
}
