package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.ThinkingBlock
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the frozen `:core` wire shape ([StreamJsonEvent]) maps to the UI transcript through
 * [StreamJsonMapper] + [foldEvent]. Mirrors the CYP-5 spike's tool-call turn sequence.
 */
class StreamJsonMapperTest {

    /** CYP-335: each wire event is stamped 1_000 ms apart, so a row's time identifies which event bore it. */
    private fun pipeline(events: List<StreamJsonEvent>): List<AgentEvent> {
        val mapper = StreamJsonMapper()
        return foldEvents(events.flatMapIndexed { i, e -> mapper.map(e, tsMs = (i + 1) * 1_000L) })
    }

    private val toolCallTurn: List<StreamJsonEvent> = listOf(
        SystemEvent(subtype = "init", sessionId = "s", uuid = "u-sys", model = "claude-opus-4-8"),
        RateLimitEvent(sessionId = "s", uuid = "u-rate"),
        AssistantEvent(
            message = AgentMessage(
                id = "m-1", role = "assistant", stopReason = "tool_use",
                content = listOf(
                    ThinkingBlock("denke nach"),
                    ToolUseBlock(
                        id = "toolu_1", name = "Bash",
                        input = buildJsonObject {
                            put("command", "echo spike-tool-ok")
                            put("description", "Echo test")
                        },
                    ),
                ),
            ),
            sessionId = "s", uuid = "u-a1",
        ),
        UserEvent(
            message = AgentMessage(
                role = "user",
                content = listOf(
                    ToolResultBlock(toolUseId = "toolu_1", content = JsonPrimitive("spike-tool-ok"), isError = false),
                ),
            ),
            sessionId = "s", uuid = "u-u1",
        ),
        AssistantEvent(
            message = AgentMessage(
                id = "m-2", role = "assistant", stopReason = "end_turn",
                content = listOf(TextBlock("The command output was: spike-tool-ok")),
            ),
            sessionId = "s", uuid = "u-a2",
        ),
        ResultEvent(subtype = "success", isError = false, result = "The command output was: spike-tool-ok", sessionId = "s", uuid = "u-r1"),
    )

    @Test
    fun toolCallTurn_mapsAndFoldsToTranscript() {
        val transcript = pipeline(toolCallTurn)

        // system → Notice; rate_limit + success result → nothing; thinking dropped.
        assertEquals(4, transcript.size, "expected Notice, ToolCall, Result, AssistantText")

        val notice = transcript[0] as AgentEvent.Notice
        assertTrue(notice.text.contains("claude-opus-4-8"))

        val tool = transcript[1] as AgentEvent.ToolCall
        assertEquals("Bash", tool.tool)
        assertEquals("echo spike-tool-ok", tool.summary, "summary prefers the 'command' key")
        assertEquals(ToolStatus.OK, tool.status, "tool_result folded the RUNNING call to OK in place")

        val result = transcript[2] as AgentEvent.Result
        assertEquals("spike-tool-ok", result.label)
        assertFalse(result.isError)

        val text = transcript[3] as AgentEvent.AssistantText
        assertEquals("The command output was: spike-tool-ok", text.text)
        assertTrue(text.complete, "end_turn stop reason marks the assistant text complete")
    }

    // --- CYP-335: every row is dated by the wire event that bore it ---

    @Test
    fun everyRow_carriesTheStampOfItsWireEvent() {
        // pipeline() stamps the Nth wire event with N*1_000. toolCallTurn is:
        //   1_000 system · 2_000 rate_limit · 3_000 assistant(tool_use) · 4_000 user(tool_result)
        //   5_000 assistant(text) · 6_000 result(success)
        val transcript = pipeline(toolCallTurn)

        assertEquals(1_000L, transcript[0].tsMs, "Notice is dated by the system/init event")
        assertEquals(3_000L, transcript[1].tsMs, "the ToolCall keeps the tool_use event's time (its START)")
        assertEquals(4_000L, transcript[2].tsMs, "the Result is dated by the tool_result event")
        assertEquals(5_000L, transcript[3].tsMs, "the assistant text is dated by its own event")
    }

    @Test
    fun toolResultFanOut_datesTheTwoRowsDifferently() {
        // ONE tool_result wire event fans out into TWO rows (the resolved ToolCall + the Result). They must not
        // share a timestamp: the tool call is dated by its start, the result by its arrival. The operator reads
        // start AND end off the transcript — that only works if the fan-out rows are dated independently.
        val mapper = StreamJsonMapper()
        val toolUse = AssistantEvent(
            message = AgentMessage(
                id = "m", stopReason = "tool_use",
                content = listOf(ToolUseBlock(id = "toolu_1", name = "Bash", input = buildJsonObject { put("command", "sleep") })),
            ),
            uuid = "a",
        )
        val toolResult = UserEvent(
            message = AgentMessage(content = listOf(ToolResultBlock(toolUseId = "toolu_1", content = JsonPrimitive("done"), isError = false))),
            uuid = "u",
        )

        mapper.map(toolUse, tsMs = 1_000L)
        val fanOut = mapper.map(toolResult, tsMs = 90_000L)

        assertEquals(2, fanOut.size, "one tool_result event produces the resolved ToolCall AND the Result")
        val resolved = fanOut[0] as AgentEvent.ToolCall
        val result = fanOut[1] as AgentEvent.Result
        assertEquals(ToolStatus.OK, resolved.status)
        assertEquals(1_000L, resolved.tsMs, "the resolved tool call still carries its START time")
        assertEquals(90_000L, result.tsMs, "the result row carries the time the result arrived")
    }

    @Test
    fun rateLimitAndSuccessResult_produceNoRows() {
        val mapper = StreamJsonMapper()
        assertTrue(mapper.map(RateLimitEvent(uuid = "r"), tsMs = 0L).isEmpty())
        assertTrue(mapper.map(ResultEvent(subtype = "success", isError = false, uuid = "x"), tsMs = 0L).isEmpty())
    }

    @Test
    fun thinkingOnlyAssistant_isDropped() {
        val mapper = StreamJsonMapper()
        val ev = AssistantEvent(
            message = AgentMessage(id = "m", stopReason = "tool_use", content = listOf(ThinkingBlock("nur denken"))),
            uuid = "u",
        )
        assertTrue(mapper.map(ev, tsMs = 0L).isEmpty())
    }

    @Test
    fun toolResultError_marksToolCallError() {
        val events = listOf(
            AssistantEvent(
                message = AgentMessage(
                    id = "m", stopReason = "tool_use",
                    content = listOf(ToolUseBlock(id = "toolu_x", name = "run_tests", input = buildJsonObject { put("path", ":app:shared") })),
                ),
                uuid = "a",
            ),
            UserEvent(
                message = AgentMessage(content = listOf(ToolResultBlock(toolUseId = "toolu_x", content = JsonPrimitive("1 failed"), isError = true))),
                uuid = "u",
            ),
        )
        val transcript = pipeline(events)
        val tool = transcript.first { it is AgentEvent.ToolCall } as AgentEvent.ToolCall
        assertEquals(ToolStatus.ERROR, tool.status)
        val result = transcript.first { it is AgentEvent.Result } as AgentEvent.Result
        assertTrue(result.isError)
    }

    @Test
    fun errorResult_emitsNotice() {
        val mapper = StreamJsonMapper()
        val rows = mapper.map(ResultEvent(subtype = "error_max_turns", isError = true, uuid = "e"), tsMs = 7_000L)
        val notice = rows.single() as AgentEvent.Notice
        assertTrue(notice.text.contains("error_max_turns"))
    }

    // --- CYP-326 #1: injected incoming/system message visibility ---

    @Test
    fun injectedUserText_mapsToIncomingSystemRow() {
        // The platform injected this incoming message (injectedSource names the injector) → it must surface as an
        // IncomingSystem row so the operator sees the trigger, with the raw text verbatim.
        val rows = pipeline(
            listOf(
                UserEvent(
                    message = AgentMessage(role = "user", content = listOf(TextBlock("/compact"))),
                    uuid = "u-inj", injectedSource = "compact-orchestrator",
                ),
            ),
        )
        val system = rows.single() as AgentEvent.IncomingSystem
        assertEquals("/compact", system.text, "the raw injected text is shown verbatim")
    }

    @Test
    fun plainUserText_stillDropped_noComposerDoubleEcho() {
        // A plain replayed user echo (injectedSource == null) stays dropped — the operator composer already echoes
        // client-side (CYP-323), so surfacing this would double it.
        val rows = pipeline(
            listOf(
                UserEvent(
                    message = AgentMessage(role = "user", content = listOf(TextBlock("hallo agent"))),
                    uuid = "u-plain", injectedSource = null,
                ),
            ),
        )
        assertTrue(rows.none { it is AgentEvent.IncomingSystem }, "a non-injected user echo must not surface as a system row")
        assertTrue(rows.isEmpty(), "a plain replayed user text yields no transcript row (dropped, as before)")
    }
}
