package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-326 §2.1 — the 3 new compact-orchestration event types share the compaction family's `▦` scan glyph
 * (reuse, not a new visual). Mutation: mis-assign the `▦` branch for the 3 new types (e.g. → `"X"`) → these
 * assertions RED. (Note: outright REMOVING them from the branch is a compile error — [EventType.groupGlyph]'s
 * `when` is exhaustive over EventType, so the compiler already guarantees every type has a glyph.)
 */
class EventCompactGlyphTest {

    @Test
    fun compactOrchestrationTypes_shareTheCompactionGroupGlyph() {
        assertEquals("▦", EventType.COMPACT_PREPARE_SENT.groupGlyph())
        assertEquals("▦", EventType.COMPACT_REQUEST_SENT.groupGlyph())
        assertEquals("▦", EventType.COMPACT_ORCHESTRATION_DONE.groupGlyph())
        // Reuse anchor: the existing family keeps its glyph (no regression).
        assertEquals("▦", EventType.COMPACT_COMPLETED.groupGlyph())
    }
}
