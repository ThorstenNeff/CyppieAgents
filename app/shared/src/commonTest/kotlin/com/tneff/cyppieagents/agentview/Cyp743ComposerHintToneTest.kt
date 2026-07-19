package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.ui.HintTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-743 — the composer hint tones must be DISTINCT per state so "unknown" never reads as "denied" at the glyph/
 * colour (which land before the text — the visible half of safe-but-silent). READ_ONLY = GATED (a permission gate),
 * UNKNOWN = ERROR (a fail-closed block). A mutation collapsing them to the same tone reddens here.
 */
class Cyp743ComposerHintToneTest {

    @Test
    fun readOnly_isGated_unknown_isError_andDistinct() {
        assertEquals(HintTone.GATED, agentComposerHintTone(AgentComposerWritability.READ_ONLY), "read-only = permission gate (·)")
        assertEquals(HintTone.ERROR, agentComposerHintTone(AgentComposerWritability.UNKNOWN), "unknown = fail-closed block (✕)")
        assertNotEquals(
            agentComposerHintTone(AgentComposerWritability.READ_ONLY),
            agentComposerHintTone(AgentComposerWritability.UNKNOWN),
            "① and ② must not share a HintTone — else 'unknown' reads as 'denied' at the glyph/colour",
        )
    }
}
