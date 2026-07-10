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
import com.tneff.cyppieagents.model.TerminalControlState
import kotlin.test.Test

/**
 * CYP-354 §5.1 (client mirror) — the read-only terminal-control mode marker rendered through the REAL [WindowHost]
 * (mirrors the CYP-324 busy / CYP-316 token host tests). `controlStateFor` is the shell's fail-closed map. Proves:
 *  - a non-MEDIATED state renders the marker under `window.<id>.mode` (glyph + label + a11y);
 *  - **honesty (absent == MEDIATED):** MEDIATED / an absent key render **no node at all** — never a fabricated marker;
 *  - the four states are visually **distinct** (◉ / → / ← / ∅) and carry the localized label.
 *  jvmTest render locale = EN.
 */
@OptIn(ExperimentalTestApi::class)
class WindowModeMarkerRenderTest {

    private fun windows(vararg ids: Pair<String, String>) = WindowManagerState(
        ids.mapIndexed { i, (id, title) -> WindowState(id, title, (i * 400).toFloat(), 0f, 380f, 240f) },
    )

    @Test
    fun modeMarker_presentForInteractive_absentForMediatedAndUnknown() = runComposeUiTest {
        val state = windows("backend" to "Backend", "frontend" to "Frontend", "db" to "DB")
        val modes = mapOf("backend" to TerminalControlState.INTERACTIVE, "frontend" to TerminalControlState.MEDIATED)
        setContent {
            MaterialTheme {
                Box(Modifier.size(1400.dp, 700.dp)) { // both axes ≥ Medium → canvas (not the pager)
                    WindowHost(
                        state = state,
                        controlStateFor = { id -> modes[id] }, // db absent → null
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
            "a" to TerminalControlState.INTERACTIVE,
            "b" to TerminalControlState.HANDING_OVER,
            "c" to TerminalControlState.HANDING_BACK,
            "d" to TerminalControlState.CONTEXT_LOST,
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(1800.dp, 700.dp)) {
                    WindowHost(state = state, controlStateFor = { id -> modes[id] }, windowContent = { Text("c ${it.id}") })
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
}
