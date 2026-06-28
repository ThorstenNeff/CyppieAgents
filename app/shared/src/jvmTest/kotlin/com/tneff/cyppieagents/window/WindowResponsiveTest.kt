package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-26: the "fit windows" affordance is a canvas-only host control (present at Medium/Expanded) and a
 * user-triggered one-shot re-tile that **heals** off-host windows back to fully visible (§2.3). Drives
 * the real [WindowHost]/[WindowCanvas] at an Expanded size.
 */
@OptIn(ExperimentalTestApi::class)
class WindowResponsiveTest {

    @Test
    fun fitAction_reTilesOffHostWindowsFullyIntoView() = runComposeUiTest {
        // Two windows shoved far off-host. clampToBounds only keeps 48 dp visible; "fit" must restore
        // the fully-visible default layout.
        val state = WindowManagerState(
            listOf(
                WindowState("a", "A", 5000f, 5000f, 300f, 200f),
                WindowState("b", "B", 6000f, 100f, 300f, 200f),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 700.dp)) { // both axes ≥ Medium → canvas (not pager)
                    WindowHost(state = state, onFit = { state.fit(isRtl = false) }, windowContent = { Text("x ${it.id}") })
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithTag(WindowTestTags.FIT).assertExists()

        onNodeWithTag(WindowTestTags.FIT).performClick()
        waitForIdle()

        // After "fit": every window is wholly within the 900×700 host (no off-host).
        assertTrue(
            state.windows.all {
                it.x >= 0f && it.x + it.width <= 900f + 1f && it.y >= 0f && it.y + it.height <= 700f + 1f
            },
            "fit must bring every window fully into the host — got ${state.windows.map { it.x to it.width }}",
        )
    }
}
