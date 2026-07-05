package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SerializationRoundTripTest {

    private inline fun <reified T> roundTrip(value: T): T {
        val json = CommJson.encodeToString(value)
        return CommJson.decodeFromString<T>(json)
    }

    @Test
    fun agentRoundTrips() {
        val a = Agent(id = "backend", name = "Backend", role = Role.WORKER, worktree = "backend")
        assertEquals(a, roundTrip(a))
    }

    @Test
    fun channelRoundTrips() {
        val c = Channel("po-backend", "po-backend", ChannelKind.HUB, listOf("po", "backend"))
        assertEquals(c, roundTrip(c))
    }

    @Test
    fun aclEntryRoundTrips() {
        val e = AclEntry("po-backend", "backend", canRead = true, canWrite = false)
        assertEquals(e, roundTrip(e))
    }

    @Test
    fun messageRoundTripsWithMeta() {
        val m = Message(
            id = "01J",
            channelId = "po-backend",
            from = "backend",
            body = "ready",
            ts = 1_782_517_200_000,
            meta = MessageMeta(inReplyTo = "01I", kind = MessageKind.STATUS),
        )
        assertEquals(m, roundTrip(m))
    }

    @Test
    fun unknownKeysAreTolerated() {
        // Simulates version drift in the stream-json protocol: a future field must not break us.
        val withExtra = """{"id":"x","channelId":"c","from":"a","body":"b","ts":1,"futureField":42}"""
        val m = CommJson.decodeFromString<Message>(withExtra)
        assertEquals("x", m.id)
    }

    @Test
    fun streamJsonResultDeserializesAndDetectsSuccess() {
        val line = """{"type":"result","subtype":"success","is_error":false,
            "total_cost_usd":0.12,"result":"done","session_id":"s1","uuid":"u1"}"""
        val ev = CommJson.decodeFromString<StreamJsonEvent>(line)
        val result = assertIs<ResultEvent>(ev)
        assertTrue(result.isSuccess)
        assertEquals("done", result.result)
    }

    @Test
    fun streamJsonErrorResultIsNotSuccess() {
        val line = """{"type":"result","subtype":"error_during_execution","is_error":true,"session_id":"s1"}"""
        val ev = assertIs<ResultEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertFalse(ev.isSuccess)
    }

    @Test
    fun streamJsonSystemInitDeserializes() {
        // Real CLI shape (CYP-13): `tools` is an ARRAY of tool names, not a count.
        val line = """{"type":"system","subtype":"init","session_id":"s1","model":"claude-opus-4-8",
            "permissionMode":"bypassPermissions","apiKeySource":"none","tools":["Read","Bash","Edit"]}"""
        val ev = assertIs<SystemEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertEquals("init", ev.subtype)
        assertEquals("bypassPermissions", ev.permissionMode)
        assertEquals(listOf("Read", "Bash", "Edit"), ev.tools)
        assertEquals(3, ev.toolCount)
    }

    @Test
    fun systemInitToleratesToolsAsIntCount() {
        // CYP-25: an older/future int shape must NOT break decoding (→ null), so the bind survives.
        val line = """{"type":"system","subtype":"init","session_id":"s1","tools":67}"""
        val ev = assertIs<SystemEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertEquals("s1", ev.sessionId) // still decoded → session can bind
        assertEquals(null, ev.tools)
    }

    @Test
    fun systemInitToleratesToolsAsObjectArray() {
        // A future array-of-objects shape: non-string elements are skipped, never thrown on.
        val line = """{"type":"system","subtype":"init","session_id":"s1","tools":[{"name":"Read"}]}"""
        val ev = assertIs<SystemEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertEquals(emptyList(), ev.tools)
    }

    @Test
    fun streamJsonAssistantToolUseDeserializes() {
        val line = """{"type":"assistant","session_id":"s1","message":{"role":"assistant",
            "content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"echo hi"}}]}}"""
        val ev = assertIs<AssistantEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        val block = assertIs<ToolUseBlock>(ev.message.content.single())
        assertEquals("Bash", block.name)
    }

    @Test
    fun streamJsonUserToolResultDeserializes() {
        val line = """{"type":"user","session_id":"s1","message":{"role":"user",
            "content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"hi","is_error":false}]}}"""
        val ev = assertIs<UserEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertIs<ToolResultBlock>(ev.message.content.single())
    }

    @Test
    fun streamJsonAssistantTextDeserializes() {
        val line = """{"type":"assistant","session_id":"s1","message":{"role":"assistant",
            "content":[{"type":"text","text":"hello"}]}}"""
        val ev = assertIs<AssistantEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertEquals("hello", assertIs<TextBlock>(ev.message.content.single()).text)
    }

    @Test
    fun assistantMessageIdIsExposed() {
        val line = """{"type":"assistant","session_id":"s1","message":{"id":"msg_123","role":"assistant",
            "content":[{"type":"text","text":"hi"}]}}"""
        val ev = assertIs<AssistantEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertEquals("msg_123", ev.messageId)
    }

    @Test
    fun roleRoundTrips_directTopLevel_onEveryTarget() {
        // CYP-217: direct top-level Role (de)serialization — the exact reified `encodeToString(Role)` /
        // `decodeFromString<Role>` path. Without `@Serializable` on the enum this fell back to runtime
        // reflection: green on jvm/android, but SerializationException on js/wasmJs. This runs on ALL targets
        // via commonTest, so it is RED on the browser targets without the fix.
        for (r in Role.entries) assertEquals(r, roundTrip(r))
        // Wire value = the @SerialName (== the constant name) → unchanged, so no DTO-ser regression.
        assertEquals("\"PO\"", CommJson.encodeToString(Role.PO))
        assertEquals(Role.PRODUCT_LEAD, CommJson.decodeFromString<Role>("\"PRODUCT_LEAD\""))
    }

    @Test
    fun roleRoundTrips_insideList() {
        val list = listOf(Role.PO, Role.WORKER, Role.PRODUCT_LEAD)
        assertEquals(list, roundTrip(list))
    }

    @Test
    fun userTurnSerializesToVerifiedStdinShape() {
        val line = UserTurn("Reply with: FIRST").toNdjsonLine()
        // Must round-trip back to a UserEvent with a single text block (the verified stdin shape).
        val ev = assertIs<UserEvent>(CommJson.decodeFromString<StreamJsonEvent>(line))
        assertEquals("Reply with: FIRST", assertIs<TextBlock>(ev.message.content.single()).text)
        assertEquals("user", ev.message.role)
    }
}
