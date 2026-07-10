package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.StubAgentSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-370 — **the composer's "usable when narrow" promise lives in the compact breakpoint, and here is its
 * guard.**
 *
 * The dead `Modifier.weight(1f).widthIn(min = COMPOSER_MIN_WIDTH)` looked like it protected the composer; it did
 * nothing (proven byte-identical with it removed). What actually keeps a narrow composer usable is the breakpoint
 * `maxWidth < COMPOSER_COMPACT_INPUT_THRESHOLD + 96.dp`: below it the send control drops its "Senden" label for a
 * glyph — **keeping its accessible name** — so the input keeps its room and nothing truncates. That property had
 * no test; whoever deleted the dead `widthIn` could just as easily have deleted the live breakpoint, believing
 * the `widthIn` was doing the work. This is the wall against that.
 *
 * The width sweep is **derived from the constant** (`threshold = COMPOSER_COMPACT_INPUT_THRESHOLD + 96`), never
 * hardcoded, so moving the constant moves the guard with it. The composer's `maxWidth` equals the window width
 * here (the window column has no horizontal inset above the composer), so a window straddling the threshold
 * straddles the breakpoint.
 *
 * In jvmTest, like every other Compose-rendering guard in this module (`ContentWindowChromeFloorGuardTest`,
 * `AgentHeaderControlsGuardTest`): `runComposeUiTest`'s capture pattern and compose-resource resolution are the
 * JVM path here, not the karma-hosted wasm/js one. The composer layout under test is `commonMain` Compose, the
 * same code the WASM app runs.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp370ComposerCompactBreakpointGuardTest {

    /** The window/composer width at which "Senden" collapses to a glyph — the constant plus the send-button chrome. */
    private val threshold = COMPOSER_COMPACT_INPUT_THRESHOLD + 96f

    private data class Send(val glyphShown: Boolean, val labelShown: Boolean, val accessibleName: String?)

    @Test
    fun belowTheThreshold_sendIsAGlyph_aboveIt_sendIsTheLabel() {
        val below = sendAt(threshold - 1f)
        val above = sendAt(threshold + 1f)

        assertTrue(
            below.glyphShown && !below.labelShown,
            "CYP-370: just below the compact threshold (${threshold - 1f} dp) the send control must be a glyph, " +
                "not the 'Senden' label — otherwise the label eats the input's width in a narrow window. " +
                "glyphShown=${below.glyphShown}, labelShown=${below.labelShown}. Did the compact breakpoint go?",
        )
        assertTrue(
            above.labelShown && !above.glyphShown,
            "CYP-370: just above the threshold (${threshold + 1f} dp) the send control must show its 'Senden' " +
                "label. labelShown=${above.labelShown}, glyphShown=${above.glyphShown}.",
        )
    }

    @Test
    fun theGlyphSendControl_keepsItsAccessibleName() {
        val below = sendAt(threshold - 1f)
        assertEquals(
            "Senden",
            below.accessibleName,
            "CYP-370: the glyph send control must keep the accessible name 'Senden' (CYP-26 §2.2). A glyph with " +
                "no name is a button a screen reader announces as nothing — the label degrades for the eye only.",
        )
    }

    @Test
    fun theThresholdStraddleIsReal_notBothSidesTheSame() {
        // Guards the guard: if the two sides ever rendered identically, the two assertions above would prove
        // nothing. They must actually differ across the one-dp line.
        val below = sendAt(threshold - 1f)
        val above = sendAt(threshold + 1f)
        assertTrue(
            below.glyphShown != above.glyphShown,
            "CYP-370: the threshold does not change the send control (glyph both sides = ${below.glyphShown}); " +
                "the straddle proves nothing. Check that the window width really equals the composer maxWidth.",
        )
    }

    private fun sendAt(width: Float): Send {
        lateinit var result: Send
        runComposeUiTest {
            val vm = AgentViewModel(StubAgentSession(), AGENT_ID, canControl = true)
            setContent {
                MaterialTheme { Box(Modifier.size(width.dp, 900.dp)) { AgentWindow(AGENT_ID, vm) } }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(AgentViewTags.sendBtn(AGENT_ID)).fetchSemanticsNodes().isNotEmpty()
            }
            val send = onNodeWithTag(AgentViewTags.sendBtn(AGENT_ID)).fetchSemanticsNode()
            result = Send(
                // Visible text only — `onAllNodesWithText` matches SemanticsProperties.Text, not ContentDescription,
                // so the glyph form's `contentDescription = "Senden"` does NOT register as a shown label here.
                glyphShown = onAllNodesWithText(GLYPH).fetchSemanticsNodes().isNotEmpty(),
                labelShown = onAllNodesWithText(LABEL).fetchSemanticsNodes().isNotEmpty(),
                accessibleName = send.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
            )
        }
        return result
    }

    private companion object {
        const val AGENT_ID = "po"
        const val LABEL = "Senden"
        const val GLYPH = "➤"
    }
}
