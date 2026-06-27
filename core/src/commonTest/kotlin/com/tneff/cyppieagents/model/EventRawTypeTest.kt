package com.tneff.cyppieagents.model

import com.tneff.cyppieagents.CommJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-37 additive: an unknown wire type is preserved in [Event.rawType] (EVENT-LOG-UI §3, "nothing
 * gets eaten") and round-trips losslessly via the `type` field; known types keep `rawType == null`.
 */
class EventRawTypeTest {

    @Test
    fun unknownType_preservesRawType_andRoundTrips() {
        val wire = """
            {"id":"x","ts":1,"seq":1,"agentId":"a","teamId":"t",
             "type":"stall.suspected","severity":"info","detail":{}}
        """.trimIndent()
        val decoded = CommJson.decodeFromString(Event.serializer(), wire)
        assertEquals(EventType.UNKNOWN, decoded.type)
        assertEquals("stall.suspected", decoded.rawType)

        // Re-encode → decode: the raw type survives (written back as `type`).
        val round = CommJson.decodeFromString(Event.serializer(), CommJson.encodeToString(Event.serializer(), decoded))
        assertEquals(EventType.UNKNOWN, round.type)
        assertEquals("stall.suspected", round.rawType)
    }

    @Test
    fun knownType_hasNullRawType() {
        val e = Event(
            id = "x", ts = 1, seq = 1, agentId = "a", teamId = "t",
            type = EventType.TOOL_CALL, severity = Severity.INFO,
        )
        assertNull(e.rawType)
        val round = CommJson.decodeFromString(Event.serializer(), CommJson.encodeToString(Event.serializer(), e))
        assertNull(round.rawType)
        assertEquals(EventType.TOOL_CALL, round.type)
    }
}
