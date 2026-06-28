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
        // A type this build doesn't model yet (a future watcher's). The 07 stall.* types are now
        // first-class (CYP-64) — see stallTypesAreFirstClass — so a budget.* type carries the sentinel.
        assertEquals(EventType.UNKNOWN, EventType.fromWire("budget.suspected"))
        assertEquals(EventType.UNKNOWN, EventType.fromWire("totally.unknown.type"))
        assertEquals(EventType.UNKNOWN, EventType.fromWire(""))
    }

    @Test
    fun stallTypesAreFirstClass() {
        // CYP-64: the 07/S11 supervision vocabulary is official now, no longer rawType-only.
        assertEquals(EventType.STALL_SUSPECTED, EventType.fromWire("stall.suspected"))
        assertEquals(EventType.NUDGE_SENT, EventType.fromWire("nudge.sent"))
        assertEquals(EventType.STALL_RECOVERED, EventType.fromWire("stall.recovered"))
        assertEquals(EventType.STALL_ESCALATED, EventType.fromWire("stall.escalated"))
    }

    @Test
    fun everyRealTypeRoundTripsThroughWire() {
        EventType.entries.filter { it != EventType.UNKNOWN }.forEach {
            assertEquals(it, EventType.fromWire(it.wire))
        }
    }
}
