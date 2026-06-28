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
        // A type this build doesn't model yet (a future watcher's). The 07 stall.* types are now
        // first-class (CYP-64) — see EventModelTest.stallTypesAreFirstClass — so they no longer
        // exercise the unknown path; a budget.* type still does.
        val wire = """
            {"id":"x","ts":1,"seq":1,"agentId":"a","projectId":"t",
             "type":"budget.escalated","severity":"info","detail":{}}
        """.trimIndent()
        val decoded = CommJson.decodeFromString(Event.serializer(), wire)
        assertEquals(EventType.UNKNOWN, decoded.type)
        assertEquals("budget.escalated", decoded.rawType)

        // Re-encode → decode: the raw type survives (written back as `type`).
        val round = CommJson.decodeFromString(Event.serializer(), CommJson.encodeToString(Event.serializer(), decoded))
        assertEquals(EventType.UNKNOWN, round.type)
        assertEquals("budget.escalated", round.rawType)
    }

    @Test
    fun knownType_hasNullRawType() {
        val e = Event(
            id = "x", ts = 1, seq = 1, agentId = "a", projectId = "t",
            type = EventType.TOOL_CALL, severity = Severity.INFO,
        )
        assertNull(e.rawType)
        val round = CommJson.decodeFromString(Event.serializer(), CommJson.encodeToString(Event.serializer(), e))
        assertNull(round.rawType)
        assertEquals(EventType.TOOL_CALL, round.type)
    }
}
