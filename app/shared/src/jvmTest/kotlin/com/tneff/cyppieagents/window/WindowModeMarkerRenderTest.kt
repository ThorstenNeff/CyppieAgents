package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.TerminalControlState
import kotlin.test.Test

/**
 * CYP-354 §5.1 (client mirror) — the read-only terminal-control mode marker rendered through the REAL [WindowHost]
 * (mirrors the CYP-324 busy / CYP-316 token host tests). `controlEventFor` is the shell's fail-closed map. Proves:
 *  - a non-MEDIATED state renders the marker under `window.<id>.mode` (glyph + label + a11y);
 *  - **honesty (absent == MEDIATED):** MEDIATED / an absent key render **no node at all** — never a fabricated marker;
 *  - the four states are visually **distinct** (◉ / → / ← / ∅) and carry the localized label;
 *  - **CYP-381:** the marker surfaces holder-identity (a chip + the "held by" a11y label) from the SAME event; a
 *    holder-less state shows the plain marker (no chip). jvmTest render locale = EN.
 */
@OptIn(ExperimentalTestApi::class)
class WindowModeMarkerRenderTest {

    private fun windows(vararg ids: Pair<String, String>) = WindowManagerState(
        ids.mapIndexed { i, (id, title) -> WindowState(id, title, (i * 400).toFloat(), 0f, 380f, 240f) },
    )

    private fun ev(state: TerminalControlState, heldBy: String? = null, since: Long? = null) =
        AgentTerminalControlEvent(agentId = "x", state = state, heldBy = heldBy, since = since)

    @Test
    fun modeMarker_presentForInteractive_absentForMediatedAndUnknown() = runComposeUiTest {
        val state = windows("backend" to "Backend", "frontend" to "Frontend", "db" to "DB")
        val modes = mapOf("backend" to ev(TerminalControlState.INTERACTIVE), "frontend" to ev(TerminalControlState.MEDIATED))
        setContent {
            MaterialTheme {
                Box(Modifier.size(1400.dp, 700.dp)) { // both axes ≥ Medium → canvas (not the pager)
                    WindowHost(
                        state = state,
                        controlEventFor = { id -> modes[id] }, // db absent → null
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }
        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // Non-MEDIATED → the marker is present: glyph form + localized label + merged a11y description.
        onNodeWithTag(WindowTestTags.mode("backend")).assertExists()
        onNodeWithText("◉", useUnmergedTree = true).assertExists()
        onNodeWithText("Interactive", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Terminal mode: Interactive").assertExists()
        // Honesty (absent == MEDIATED): an explicit MEDIATED renders NO node; an absent agent renders NO node.
        // Mutation: make MEDIATED render a marker (drop the `MEDIATED -> null` guard) → frontend would show one → RED.
        onNodeWithTag(WindowTestTags.mode("frontend")).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.mode("db")).assertDoesNotExist()
    }

    @Test
    fun modeMarker_fourStates_areVisuallyDistinct_withLocalizedLabels() = runComposeUiTest {
        val state = windows("a" to "A", "b" to "B", "c" to "C", "d" to "D")
        val modes = mapOf(
            "a" to ev(TerminalControlState.INTERACTIVE),
            "b" to ev(TerminalControlState.HANDING_OVER),
            "c" to ev(TerminalControlState.HANDING_BACK),
            "d" to ev(TerminalControlState.CONTEXT_LOST),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(1800.dp, 700.dp)) {
                    WindowHost(state = state, controlEventFor = { id -> modes[id] }, windowContent = { Text("c ${it.id}") })
                }
            }
        }
        // Each state's glyph is distinct (the form carries meaning, WCAG 1.4.1) and each carries its localized label.
        onNodeWithText("◉", useUnmergedTree = true).assertExists()
        onNodeWithText("→", useUnmergedTree = true).assertExists()
        onNodeWithText("←", useUnmergedTree = true).assertExists()
        onNodeWithText("∅", useUnmergedTree = true).assertExists()
        onNodeWithText("Handing over…", useUnmergedTree = true).assertExists()
        onNodeWithText("Handing back…", useUnmergedTree = true).assertExists()
        onNodeWithText("Context lost", useUnmergedTree = true).assertExists()
        // All four markers are present (each a non-MEDIATED state).
        listOf("a", "b", "c", "d").forEach { onNodeWithTag(WindowTestTags.mode(it)).assertExists() }
    }

    @Test
    fun modeMarker_surfacesHolderIdentity_whenHeld_andHidesChip_whenHolderless() = runComposeUiTest {
        val state = windows("held" to "Held", "plain" to "Plain")
        val modes = mapOf(
            // INTERACTIVE held by an operator → holder chip + "held by" a11y (since is available but not visibly formatted yet).
            "held" to ev(TerminalControlState.INTERACTIVE, heldBy = "alice", since = 1_700_000_000_000L),
            // HANDING_OVER with no holder → the plain marker, NO holder chip (honest: nothing to attribute yet).
            "plain" to ev(TerminalControlState.HANDING_OVER, heldBy = null),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(1400.dp, 700.dp)) {
                    WindowHost(state = state, controlEventFor = { id -> modes[id] }, windowContent = { Text("c ${it.id}") })
                }
            }
        }
        // Holder present → the visible chip + the holder-bearing merged a11y label.
        // Mutation: drop the `if (holder != null)` chip / use `a11y_terminal_ctl` unconditionally → RED.
        onNodeWithTag(WindowTestTags.modeHolder("held"), useUnmergedTree = true).assertExists()
        onNodeWithText("· alice", useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Terminal mode: Interactive · held by alice").assertExists()
        // Holder-less → the marker exists but WITHOUT a holder chip (never a fabricated holder).
        onNodeWithTag(WindowTestTags.mode("plain")).assertExists()
        onNodeWithTag(WindowTestTags.modeHolder("plain"), useUnmergedTree = true).assertDoesNotExist()
    }
}
