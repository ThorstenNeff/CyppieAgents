package com.tneff.cyppieagents.model

import com.tneff.cyppieagents.CommJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * CYP-35 wire guard for the shared Event-Log read contract. The tolerant `EventType` decode is the
 * regression guard for 07's additive `stall.*` types (PRD §4.1 / §9): an unknown type must decode to
 * [EventType.UNKNOWN], never throw — the same resilience as [TolerantToolsSerializer].
 */
class EventModelTest {

    @Test
    fun event_roundTripsThroughCommJson() {
        val original = Event(
            id = "01J0ABCDEF",
            ts = 1_719_000_000_000,
            seq = 42,
            sourceTs = 1_718_999_999_000,
            agentId = "backend",
            teamId = "team-1",
            sessionId = "sess-9",
            correlationId = "corr-7",
            type = EventType.TOOL_CALL,
            severity = Severity.WARN,
            detail = buildJsonObject {
                put("toolName", "Bash")
                put("toolUseId", "tu_1")
            },
        )
        val decoded = CommJson.decodeFromString(Event.serializer(), CommJson.encodeToString(Event.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun eventType_encodesAsWireString() {
        val json = CommJson.encodeToString(EventType.serializer(), EventType.LOG_DROPPED)
        assertEquals("\"log.dropped\"", json)
    }

    @Test
    fun eventType_unknownWire_decodesToUnknown_neverThrows() {
        // A type a newer server (or 07) emits that this build doesn't model.
        val future = CommJson.decodeFromString(EventType.serializer(), "\"stall.suspected\"")
        assertEquals(EventType.UNKNOWN, future)
    }

    @Test
    fun event_withUnknownType_decodesTolerantly() {
        val wire = """
            {"id":"x","ts":1,"seq":1,"agentId":"a","teamId":"t",
             "type":"nudge.sent","severity":"info","detail":{}}
        """.trimIndent()
        val e = CommJson.decodeFromString(Event.serializer(), wire)
        assertEquals(EventType.UNKNOWN, e.type)
        assertEquals(Severity.INFO, e.severity)
        assertEquals(JsonObject(emptyMap()), e.detail)
    }

    @Test
    fun severity_encodesLowercase() {
        assertTrue(CommJson.encodeToString(Severity.serializer(), Severity.ERROR).contains("error"))
    }
}
