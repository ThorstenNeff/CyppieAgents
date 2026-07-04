package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the WS frame contract codec ([AgentWsClient] uses exactly this): server→client frames
 * decode 1:1 into [StreamJsonEvent] via [CommJson], and a client→client [UserTurn] serializes to the
 * agreed `{"text":"…"}` shape. The socket lifecycle itself is verified once the route lands (CYP-13).
 */
class AgentWsCodecTest {

    private fun decode(frame: String): StreamJsonEvent =
        CommJson.decodeFromString(StreamJsonEvent.serializer(), frame)

    @Test
    fun systemInitFrame_decodes() {
        val ev = decode("""{"type":"system","subtype":"init","session_id":"s","uuid":"u","model":"claude-opus-4-8"}""")
        assertTrue(ev is SystemEvent)
        assertEquals("init", (ev as SystemEvent).subtype)
        assertEquals("claude-opus-4-8", ev.model)
    }

    @Test
    fun assistantToolUseFrame_decodes() {
        val ev = decode(
            """{"type":"assistant","session_id":"s","uuid":"u","message":{"role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"echo hi"}}]}}"""
        )
        assertTrue(ev is AssistantEvent)
        val block = (ev as AssistantEvent).message.content.single() as ToolUseBlock
        assertEquals("toolu_1", block.id)
        assertEquals("Bash", block.name)
    }

    @Test
    fun unknownFields_areIgnored() {
        // The stream-json protocol is version-sensitive; CommJson must tolerate unmodelled fields.
        val ev = decode("""{"type":"result","subtype":"success","is_error":false,"some_future_field":42,"uuid":"u"}""")
        assertTrue(ev is ResultEvent)
        assertTrue((ev as ResultEvent).isSuccess)
    }

    @Test
    fun storedAgentEventFrame_decodes_withSeqCursorAndInnerEvent() {
        // CYP-198/204: the server→client frame is now a StoredAgentEvent wrapper (for the `seq` cursor); the
        // client reads the wrapper, tracks `seq`, and renders `.event` exactly as before.
        val stored = CommJson.decodeFromString(
            StoredAgentEvent.serializer(),
            """{"seq":7,"agentId":"backend","projectId":"p","tsMs":123,"event":{"type":"result","subtype":"success","is_error":false,"uuid":"u"}}""",
        )
        assertEquals(7L, stored.seq)
        assertEquals("backend", stored.agentId)
        assertTrue(stored.event is ResultEvent)
        assertTrue((stored.event as ResultEvent).isSuccess)
    }

    @Test
    fun userTurn_serializesToContractShape() {
        // Client→Server frame is exactly {"text":"…"} — the server reshapes it to the stdin NDJSON.
        assertEquals(
            """{"text":"hallo agent"}""",
            CommJson.encodeToString(UserTurn.serializer(), UserTurn("hallo agent")),
        )
    }
}
