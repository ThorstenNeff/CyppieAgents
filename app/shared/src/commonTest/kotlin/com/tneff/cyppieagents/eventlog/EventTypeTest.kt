package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTypeTest {

    @Test
    fun fromWire_mapsKnownIncludingLogDropped() {
        assertEquals(EventType.TOOL_CALL, EventType.fromWire("tool.call"))
        assertEquals(EventType.LOG_DROPPED, EventType.fromWire("log.dropped"))
    }

    @Test
    fun fromWire_unknownIsTolerantSentinel() {
        assertEquals(EventType.UNKNOWN, EventType.fromWire("stall.suspected")) // future 07 type, additive
        assertEquals(EventType.UNKNOWN, EventType.fromWire(""))
    }

    @Test
    fun everyRealTypeRoundTripsThroughWire() {
        EventType.entries.filter { it != EventType.UNKNOWN }.forEach {
            assertEquals(it, EventType.fromWire(it.wire))
        }
    }
}
