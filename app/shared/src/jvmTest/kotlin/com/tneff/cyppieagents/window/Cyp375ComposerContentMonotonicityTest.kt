package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.StubAgentSession
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-375 — **the composer input's non-monotonicity is an accepted re-split, and this pins that it stays one.**
 *
 * Measured across a rising window width: the input field grows to 288 dp at window 372, then **drops to 250 dp**
 * at 376 as the send button switches from a 60 dp glyph to the 102 dp word "Senden", then grows again. The drop
 * is real and it is accepted (P3, both states usable) — it cannot be tuned away, because at any switch width the
 * glyph-input and word-input differ by the send-button delta regardless of where the threshold sits (see the
 * comment on `MessageComposer`). Smoothing it would mean reserving the word width in the narrow band the compact
 * breakpoint exists to protect.
 *
 * What IS invariant, and what this guards: **input + send-button width never shrinks as the window grows.** The
 * input's *share* jumps at the breakpoint; the composer's *total content* does not. That is the difference
 * between "the field gives width to the send label" (accepted) and "the composer loses width" (a regression this
 * would catch — a spurious cap, a width-gated spacer, a `widthIn(max)` that bites).
 *
 * The single test asserts both facets over one width sweep: (1) the total never falls, and (2) the input's one
 * accepted shrink is paid for by the send button growing at least as much, at most once — tying the drop to its
 * cause. An uncompensated drop, or a second one, fails.
 *
 * jvmTest, like every composer/chrome render guard here — the sweep compares measurements across separate
 * renders (the wasm-unsafe pattern, CYP-376), so it is JVM by the same real tool limit.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp375ComposerContentMonotonicityTest {

    private data class Split(val window: Float, val input: Float, val send: Float) {
        val total get() = input + send
    }

    /**
     * Straddles the breakpoint (376 = threshold 280 + 96 dp send chrome) with room on both sides. Step 16 keeps
     * 368 (glyph) and 384 (word) both sampled — so the glyph->word drop is captured — in 12 renders, not 46. Both
     * facets share this one sweep in a single @Test (JUnit builds a fresh instance per method, so a second method
     * would re-render the lot).
     */
    private val widths: List<Float> = (320..500 step 16).map { it.toFloat() }

    @Test
    fun composerContentIsMonotonic_andTheInputsOnlyShrinkIsTheGlyphToWordReSplit() {
        val measured = widths.map { measure(it) }

        // (1) The composer's total content (input + send) never shrinks as the window grows. The input may hand
        // width to the send label at the breakpoint; the TOTAL must not fall — that would be a real loss.
        measured.zipWithNext().forEach { (a, b) ->
            assertTrue(
                b.total >= a.total - EPS,
                "CYP-375: the composer's total content (input + send) shrank from ${a.total} dp at window " +
                    "${a.window} to ${b.total} dp at ${b.window}, while the window GREW — a real loss (a spurious " +
                    "cap / width-gated element eating the composer), not the accepted re-split.\n  $a -> $b",
            )
        }

        // (2) The input's one accepted shrink is paid for by the send button growing at least as much (the
        // glyph->word re-split), and it happens at most once. An uncompensated or second drop fails.
        val shrinks = measured.zipWithNext().filter { (a, b) -> b.input < a.input - EPS }
        shrinks.forEach { (a, b) ->
            val inputLost = a.input - b.input
            val sendGained = b.send - a.send
            assertTrue(
                sendGained >= inputLost - EPS,
                "CYP-375: the input shrank ${inputLost} dp (window ${a.window}->${b.window}) but the send button " +
                    "grew only ${sendGained} dp — an UNcompensated loss, not the accepted glyph->word re-split.\n" +
                    "  $a -> $b",
            )
        }
        assertTrue(
            shrinks.size <= 1,
            "CYP-375: the input shrinks ${shrinks.size} times across the sweep; the accepted quirk is exactly ONE " +
                "drop, at the glyph->word breakpoint. More than one means a second width-dependent change crept " +
                "into the composer.\n  " + shrinks.joinToString("\n  ") { (a, b) -> "$a -> $b" },
        )
    }

    private fun measure(width: Float): Split {
        lateinit var split: Split
        runComposeUiTest {
            val vm = AgentViewModel(StubAgentSession(), AGENT_ID, canControl = true)
            setContent { MaterialTheme { Box(Modifier.size(width.dp, 900.dp)) { AgentWindow(AGENT_ID, vm) } } }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(AgentViewTags.input(AGENT_ID)).fetchSemanticsNodes().isNotEmpty()
            }
            fun w(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                .let { (it.right - it.left).value }
            split = Split(width, w(AgentViewTags.input(AGENT_ID)), w(AgentViewTags.sendBtn(AGENT_ID)))
        }
        return split
    }

    private companion object {
        const val AGENT_ID = "po"
        const val EPS = 0.5f
    }
}
