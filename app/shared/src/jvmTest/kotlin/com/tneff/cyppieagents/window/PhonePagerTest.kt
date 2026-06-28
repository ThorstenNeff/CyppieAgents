package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-50 / S10: drives the **real** [WindowHost] through Compose UI-test infra at forced sizes to
 * prove the Window-Size-Class mode switch (the mandated breakpoint primitive):
 *
 * - **pager** as soon as EITHER axis is `Compact` (portrait phone OR landscape phone),
 * - **canvas** only when BOTH axes are ≥ `Medium`,
 * - the two modes are mutually exclusive by tag (`window.host` ⊕ `phonePager.pager`).
 *
 * M3 buckets: width Compact < 600 dp, Medium 600–839; height Compact < 480 dp, Medium 480–899. Sizes
 * are chosen to land in a single intended bucket and to fit the default test surface.
 */
@OptIn(ExperimentalTestApi::class)
class PhonePagerTest {

    private fun threeWindows() = WindowManagerState(
        listOf(
            WindowState("po", "Product Owner", 0f, 0f, 200f, 150f),
            WindowState("frontend", "Frontend", 0f, 0f, 200f, 150f),
            WindowState("backend", "Backend", 0f, 0f, 200f, 150f),
        ),
    )

    @Test
    fun portraitPhone_widthCompact_rendersPagerNotCanvas() = runComposeUiTest {
        val state = threeWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) { // width Compact, height Medium
                    WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
                }
            }
        }

        onNodeWithTag(PhonePagerTags.PAGER).assertExists()
        onNodeWithTag(WindowTestTags.HOST).assertDoesNotExist()
        // Pager opens on the focused window (shared anchor) and reuses window.<id>.content.
        onNodeWithTag(PhonePagerTags.page("backend")).assertExists()
        onNodeWithTag(WindowTestTags.content("backend"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun landscapePhone_heightCompact_rendersPagerNotCanvas() = runComposeUiTest {
        // THE either-axis guard: width is Medium (≥600) but height is Compact (<480) → still pager.
        // If the breakpoint regressed to width-only, this would render the canvas → RED.
        val state = threeWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(760.dp, 360.dp)) { // width Medium, height Compact
                    WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
                }
            }
        }

        onNodeWithTag(PhonePagerTags.PAGER).assertExists()
        onNodeWithTag(WindowTestTags.HOST).assertDoesNotExist()
    }

    @Test
    fun tablet_bothAtLeastMedium_rendersCanvasNotPager() = runComposeUiTest {
        val state = threeWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(760.dp, 700.dp)) { // both Medium → canvas, unchanged
                    WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        onNodeWithTag(PhonePagerTags.PAGER).assertDoesNotExist()
        // Canvas still lays out the real floating windows.
        onNodeWithTag(WindowTestTags.window("po")).assertExists()
    }

    @Test
    fun multiplePages_showIndicatorWithActiveDotOnFocusedPage() = runComposeUiTest {
        val state = threeWindows() // focused = "backend" (last)
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) {
                    WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
                }
            }
        }

        onNodeWithTag(PhonePagerTags.INDICATOR).assertExists()
        // A dot per page, tappable.
        onNodeWithTag(PhonePagerTags.dot("po")).assertExists()
        onNodeWithTag(PhonePagerTags.dot("backend")).assertExists()
        // Active-state marker is present ONLY for the focused page (shape/size, not colour alone).
        onNodeWithTag(PhonePagerTags.dotActive("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(PhonePagerTags.dotActive("po"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun singleWindow_pagerShowsNoIndicatorChrome() = runComposeUiTest {
        // Disclosure honesty (CYP-54 §6): one window must not advertise more pages via an indicator.
        val state = WindowManagerState(listOf(WindowState("po", "Product Owner", 0f, 0f, 200f, 150f)))
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) {
                    WindowHost(state = state, windowContent = { Text("stub ${it.id}") })
                }
            }
        }

        onNodeWithTag(PhonePagerTags.PAGER).assertExists()
        onNodeWithTag(PhonePagerTags.INDICATOR).assertDoesNotExist()
    }
}
