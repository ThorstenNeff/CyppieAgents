package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.AgentViewModel
import com.tneff.cyppieagents.agentview.AgentViewTags
import com.tneff.cyppieagents.agentview.AgentWindow
import com.tneff.cyppieagents.agentview.HEADER_LABELLED_CONTROLS_MIN_WIDTH
import com.tneff.cyppieagents.agentview.StubAgentSession
import com.tneff.cyppieagents.connector.ConnectorTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-369 / CYP-350 — **a control is never measured with the room the markers left over.**
 *
 * At the tiled minimum width of 320 dp the agent header rendered `Stop` and `Restart` at **`w = 0 dp`,
 * `displayed = false`**, and `Start` at **15 dp wide and 116 dp tall** — a column of stacked letters. An operator
 * could neither stop nor restart an agent in a tiled window. Nothing said so: from the outside, a control that is
 * absent and a control that is zero dp wide are the same thing.
 *
 * **Why it happened.** `Row` measures its unweighted children **first, in order**, each against what the previous
 * ones left. The controls sat *after* a `Spacer(Modifier.weight(1f))`, so the identity cluster — status 74 dp plus
 * a **199 dp** fidelity badge — ate the row before the buttons were ever measured. The weight was on a spacer
 * *between* the two groups, which reserves nothing for either.
 *
 * **Two fixes, and neither is sufficient alone** — this is the arithmetic that decided the design:
 * ```
 * usable at 320 dp                                                          = 304
 * status 74 + badge 199 + labels (59+58+76) + 4 gaps of 8                   = 498   (does not fit)
 * glyph badge (48) instead of the labelled one                              = 347   (still does not fit)
 * glyph badge + glyph controls (3 x 48)                                     = 298   (fits, 6 dp spare)
 * ```
 * So `maxLines = 1` could never have fixed it — wrapping was the *symptom*. Nor could the glyph badge alone.
 *
 * **What this guard pins, in the order the defect broke them:**
 *  1. every control is a real WCAG 2.5.8 target (>= 24 dp on both axes) and displayed, at every width;
 *  2. the header is a single line (its height does not grow) at every width — CYP-350;
 *  3. the glyph controls keep the label as their accessible NAME, so the eye loses the word and the screen
 *     reader loses nothing. A size assertion cannot see this, and it is exactly what rots silently.
 *
 * The width sweep is derived from [HEADER_LABELLED_CONTROLS_MIN_WIDTH], never hardcoded twice: move the constant
 * and the sweep follows it to the dp on either side.
 *
 * **A note for whoever tries to mutate this guard.** Reverting *either* fix alone leaves it green, and that is
 * correct rather than toothless: it asserts the property ("a control is a usable target"), not one implementation
 * of it. With the badge compacted, even the old spacer layout leaves the controls room at 320 dp; with the
 * distribution rule, the labelled controls keep their width whatever the badge does. **The defect needed both
 * conditions**, so the mutation that reproduces it reverts both — and then all three tests fail, with `start`
 * back at exactly `15 x 116 dp` and the header back at `164 dp`. Measured, not recalled.
 */
@OptIn(ExperimentalTestApi::class)
class AgentHeaderControlsGuardTest {

    /** WCAG 2.5.8 (Target Size, Minimum). Not a style token — the reason the glyph form is an `IconButton`. */
    private val minTargetDp = 24f

    private val switch = HEADER_LABELLED_CONTROLS_MIN_WIDTH

    /** Straddles the switch to the dp, plus the width that actually shipped broken (320 = the tiled minimum). */
    private val widths = listOf(TILED_CONTENT_WINDOW_MIN_WIDTH, 400f, switch - 1f, switch, switch + 120f)

    private data class Probe(
        val headerHeight: Float,
        val badgeWidth: Float,
        val controls: Map<String, ControlProbe>,
    )

    private data class ControlProbe(val width: Float, val height: Float, val displayed: Boolean, val a11yName: String?)

    @Test
    fun everyLifecycleControl_isAUsableTarget_atEveryWidth() {
        widths.forEach { w ->
            val probe = measure(w)
            probe.controls.forEach { (name, c) ->
                assertTrue(
                    c.displayed,
                    "CYP-369 [$w dp]: `$name` is not displayed. It shipped at w=0 dp — the identity cluster was " +
                        "measured first and left the controls nothing. Do not put the weight on a Spacer between " +
                        "two groups; put it on the group that may yield (the markers), never on the controls.",
                )
                assertTrue(
                    c.width >= minTargetDp && c.height >= minTargetDp,
                    "CYP-369 [$w dp]: `$name` is ${c.width}x${c.height} dp, below the WCAG 2.5.8 minimum of " +
                        "$minTargetDp dp. `Start` shipped at 15 x 116 dp — wide enough to see, too narrow to hit.",
                )
            }
        }
    }

    /**
     * CYP-350 — the header is one line at every width. The 164 dp header at 320 dp was not a design; it was three
     * labels that could not fit beside each other, one of them stacked letter by letter down the row.
     */
    @Test
    fun theHeaderNeverWraps() {
        val heights = widths.associateWith { measure(it).headerHeight }
        val single = heights.values.distinct()
        assertEquals(
            1,
            single.size,
            "CYP-350: the header must be the same single-line height at every width. Measured: " +
                heights.entries.joinToString { "${it.key} dp -> ${it.value} dp" } +
                ". A taller header at a narrower width means a row wrapped.",
        )
    }

    /**
     * The glyph form drops the WORD, never the meaning. Without this the compact header would be a silent
     * accessibility regression that every geometric assertion above happily reports as green.
     */
    @Test
    fun compactControls_keepTheirLabelAsTheAccessibleName() {
        val compact = measure(switch - 1f)
        val labelled = measure(switch)

        // Precondition, so this test cannot pass by measuring the labelled form twice: below the switch the badge
        // is the glyph-only 48 dp target; at the switch it carries its word again.
        assertTrue(
            compact.badgeWidth < labelled.badgeWidth,
            "precondition: at ${switch - 1f} dp the badge must be compact (measured ${compact.badgeWidth} dp) " +
                "and at $switch dp labelled (${labelled.badgeWidth} dp) — otherwise this test proves nothing",
        )

        compact.controls.forEach { (name, c) ->
            val labelledName = labelled.controls.getValue(name).a11yName
            assertEquals(
                labelledName,
                c.a11yName,
                "CYP-350 [$name]: the glyph control must carry the same accessible name as its labelled twin. " +
                    "A glyph with no name is a button a screen reader announces as nothing.",
            )
            assertTrue(!c.a11yName.isNullOrBlank(), "CYP-350 [$name]: accessible name is missing")
        }
    }

    // --- measurement ------------------------------------------------------------------------------------------

    private fun measure(width: Float): Probe {
        lateinit var probe: Probe
        runComposeUiTest {
            // An operator, RUNNING is irrelevant here: `enabled` does not change a node's bounds, and a disabled
            // control must still be a target (it is announced, and it becomes enabled without relayout).
            val vm = AgentViewModel(StubAgentSession(), AGENT_ID, canControl = true)
            setContent {
                MaterialTheme {
                    Box(Modifier.size(width.dp, 900.dp)) {
                        AgentWindow(agentId = AGENT_ID, viewModel = vm, terminalGatedNote = true)
                    }
                }
            }
            waitUntil { onAllNodesWithTag(AgentViewTags.header(AGENT_ID)).fetchSemanticsNodes().isNotEmpty() }

            fun bounds(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            fun accessibleName(tag: String): String? {
                val merged = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull() ?: return null
                merged.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.let { return it }
                return merged.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            }
            fun control(tag: String): ControlProbe {
                val r = bounds(tag)
                return ControlProbe(
                    width = (r.right - r.left).value,
                    height = (r.bottom - r.top).value,
                    displayed = runCatching {
                        onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                    }.isSuccess,
                    // The labelled form names itself through its Text child; the glyph form through an explicit
                    // `contentDescription`. Read both from the MERGED tree — the node an assistive technology
                    // actually reaches — so the two forms are compared on the same footing.
                    a11yName = accessibleName(tag),
                )
            }

            val header = bounds(AgentViewTags.header(AGENT_ID))
            probe = Probe(
                headerHeight = (header.bottom - header.top).value,
                badgeWidth = bounds(ConnectorTags.fidelityBadge(AGENT_ID)).let { (it.right - it.left).value },
                controls = mapOf(
                    "start" to control(AgentViewTags.startBtn(AGENT_ID)),
                    "stop" to control(AgentViewTags.stopBtn(AGENT_ID)),
                    "restart" to control(AgentViewTags.restartBtn(AGENT_ID)),
                ),
            )
        }
        return probe
    }

    private companion object {
        const val AGENT_ID = "po"
    }
}
