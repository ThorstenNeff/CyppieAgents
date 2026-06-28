package com.tneff.cyppieagents.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-99: the shared toned hint. Two guarantees, both mutation-provable:
 * - **WCAG 1.4.1 — colour is never the sole carrier:** every tone has a DISTINCT non-emoji text glyph
 *   ([hintGlyph]), so the tones differ without colour. The pure mapping test turns RED if a glyph is
 *   changed/duplicated.
 * - **Renders the tag + glyph + message:** the hint node carries the design tag, its tone glyph, and the
 *   message (the meaning lives in the text). Messages here are letter-`i`-free so the INFO glyph `i` is
 *   unambiguous.
 */
@OptIn(ExperimentalTestApi::class)
class TonedHintTest {

    @Test
    fun hintGlyph_isDistinctPerTone_nonEmoji() {
        val glyphs = HintTone.values().map { hintGlyph(it) }
        assertEquals(glyphs.size, glyphs.toSet().size, "each tone has a distinct glyph")
        assertEquals("!", hintGlyph(HintTone.EFFECT_DEFERRED))
        assertEquals("✕", hintGlyph(HintTone.ERROR))
        assertEquals("i", hintGlyph(HintTone.INFO))
        assertEquals("·", hintGlyph(HintTone.GATED))
    }

    @Test
    fun tonedHint_rendersTag_glyph_andMessage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    TonedHint("saved not yet", HintTone.EFFECT_DEFERRED, "h.effect")
                    TonedHint("operator only", HintTone.GATED, "h.gate")
                    TonedHint("a note here", HintTone.INFO, "h.note")
                    TonedHint("boom failed", HintTone.ERROR, "h.error")
                }
            }
        }
        // Tags (the design hint ids) present per tone.
        onNodeWithTag("h.effect").assertExists()
        onNodeWithTag("h.gate").assertExists()
        onNodeWithTag("h.note").assertExists()
        onNodeWithTag("h.error").assertExists()
        // Meaning carried by the text (not colour). The per-tone glyph is proven by hintGlyph_* above.
        onNodeWithText("saved not yet", substring = true).assertExists()
        onNodeWithText("boom failed", substring = true).assertExists()
        // The attention glyph renders alongside the effect-deferred message (non-colour signal).
        onNodeWithText("!", substring = true).assertExists()
    }
}
