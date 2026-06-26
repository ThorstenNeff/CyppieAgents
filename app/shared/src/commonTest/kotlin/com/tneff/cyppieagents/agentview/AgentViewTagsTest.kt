package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the exact `testTag` strings against the Test-Contract v0.4 §2. Tags are an API with QA, so
 * a silent format change here should break this test, not the tester's flows.
 */
class AgentViewTagsTest {

    @Test
    fun namedElementTags_matchContract() {
        assertEquals("agent.backend.stream", AgentViewTags.stream("backend"))
        assertEquals("agent.backend.input", AgentViewTags.input("backend"))
        assertEquals("agent.backend.sendBtn", AgentViewTags.sendBtn("backend"))
    }

    @Test
    fun eventTags_indexedAndKindQualified() {
        assertEquals("agent.po.event.0", AgentViewTags.event("po", 0))
        assertEquals("agent.po.event.3.toolCall", AgentViewTags.event("po", 3, EventKind.TOOL_CALL))
        assertEquals("agent.po.event.1.assistantText", AgentViewTags.event("po", 1, EventKind.ASSISTANT_TEXT))
        assertEquals("agent.po.event.2.toolResult", AgentViewTags.event("po", 2, EventKind.TOOL_RESULT))
    }

    @Test
    fun allSegments_satisfyContractCharset() {
        // v0.4 §2: every segment value is [A-Za-z0-9-]+ (no dots beyond the separators).
        val tag = AgentViewTags.event("po-frontend", 5, EventKind.TOOL_CALL)
        val segments = tag.split(".")
        assertEquals(listOf("agent", "po-frontend", "event", "5", "toolCall"), segments)
        assertTrue(segments.all { it.matches(Regex("[A-Za-z0-9-]+")) })
    }
}
