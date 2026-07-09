package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-326 #1 (visibility) — the synthetic transcript event the orchestrator's `send` seam records so a
 * platform-injected message (PREPARE_TEXT / `/compact`) is visible in the agent window BEFORE the reaction.
 * The [UserEvent.injectedSource] marker distinguishes it from a plain replayed user echo (which the client
 * drops), so it NEVER double-echoes the CYP-323 operator composer. This is the wire contract Dev renders against.
 */
class CompactInjectVisibilityTest {

    @Test
    fun injectedUserEvent_carriesTheMarkerAndText() {
        val ev = CompactOrchestrator.injectedUserEvent(CompactOrchestrator.PREPARE_TEXT)
        assertEquals("compact-orchestrator", ev.injectedSource)
        assertEquals(CompactOrchestrator.INJECTED_SOURCE, ev.injectedSource)
        assertEquals("user", ev.message.role)
        assertEquals(CompactOrchestrator.PREPARE_TEXT, (ev.message.content.single() as TextBlock).text)
    }

    @Test
    fun injectedUserEvent_serializesWithInjectedSource_andRoundTrips() {
        val json = CommJson.encodeToString(StreamJsonEvent.serializer(), CompactOrchestrator.injectedUserEvent("/compact"))
        assertTrue(json.contains("\"injected_source\":\"compact-orchestrator\""), "the wire marker the client keys on: $json")
        assertTrue(json.contains("/compact"), "the injected text is carried")
        val back = CommJson.decodeFromString(StreamJsonEvent.serializer(), json) as UserEvent
        assertEquals("compact-orchestrator", back.injectedSource)
    }

    @Test
    fun plainUserEvent_hasNullInjectedSource_soReplayedEchoStaysDropped() {
        // A real CLI user event (tool_result / replayed echo) has no injected_source → the client drops the text
        // echo as today (no double-echo with the composer). This is the distinguishing contract.
        val plain = CommJson.decodeFromString<StreamJsonEvent>(
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"hi"}]},"session_id":"s"}""",
        ) as UserEvent
        assertNull(plain.injectedSource)
    }
}
