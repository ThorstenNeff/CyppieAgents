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
 * CYP-55: the badge **render plumbing** through the real [WindowHost]. `badgeFor` is the shell's
 * fail-closed map (a `null` means "no source"); these tests prove the badge reaches the right slot in
 * each mode and that a `null` source renders **no node** (the absence anchor QA relies on):
 * - **Canvas:** the badge lands in the `FloatingWindow` title bar under `windowBadge.<id>` + its
 *   variant node; a window with no source has no `windowBadge.<id>` at all.
 * - **Pager:** the badge reuses the existing `phonePager.page.<id>.badge` dot slot (CYP-54 §6); a
 *   page with no source has no badge node.
 */
@OptIn(ExperimentalTestApi::class)
class WindowBadgeHostTest {

    private fun twoWindows() = WindowManagerState(
        listOf(
            WindowState("comm", "Kommunikation", 0f, 0f, 300f, 220f),
            WindowState("frontend", "Frontend", 320f, 0f, 300f, 220f),
        ),
    )

    @Test
    fun canvas_badgeInTitleBar_presentForSource_absentForNull() = runComposeUiTest {
        val state = twoWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(900.dp, 700.dp)) { // both axes ≥ Medium → canvas
                    WindowHost(
                        state = state,
                        badgeFor = { id -> if (id == "comm") WindowBadge.Count(4) else null },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // comm has a source → container + count variant rendered in its title bar.
        onNodeWithTag(WindowBadgeTags.badge("comm")).assertExists()
        onNodeWithTag(WindowBadgeTags.count("comm")).assertExists()
        // frontend has no source → fail-closed: no badge node at all (absence anchor).
        onNodeWithTag(WindowBadgeTags.badge("frontend")).assertDoesNotExist()
    }

    @Test
    fun pager_badgeOnDot_reusesPagerSlot_absentForNull() = runComposeUiTest {
        val state = twoWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 640.dp)) { // width Compact → phone pager
                    WindowHost(
                        state = state,
                        badgeFor = { id -> if (id == "frontend") WindowBadge.Attention else null },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(PhonePagerTags.PAGER).assertExists() // pager mode (not canvas)
        // frontend's dot carries the badge via the reused pager slot; comm's dot does not. The dot is a
        // merging (clickable) node, so its inner badge is addressed unmerged — same convention the
        // existing pager tests use for `dotActive` / `window.content`.
        onNodeWithTag(PhonePagerTags.badge("frontend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(PhonePagerTags.badge("comm"), useUnmergedTree = true).assertDoesNotExist()
    }
}
