package com.tneff.cyppieagents.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-281 — the ProjectSwitcherBar measures its own width and passes `compact` to the trailing slot: true on a
 * narrow bar (~<400dp) so the theme toggle goes icon-only and hands its width back to the active-project label,
 * false when there is room for the full "Thema: <mode>" form.
 *
 * Mutation proof: change/neuter the `maxWidth < 400.dp` threshold → the narrow assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectSwitcherBarCompactTest {

    private val default = Project("default", "Default")

    private fun capturedCompactAt(test: androidx.compose.ui.test.ComposeUiTest, widthDp: Int): Boolean? = test.run {
        var captured: Boolean? = null
        setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp)) {
                    val vm = remember { ProjectViewModel(StubProjectRepository(listOf(default), "default"), editable = true) }
                    ProjectSwitcherBar(vm, trailing = { compact -> captured = compact })
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.ACTIVE).fetchSemanticsNodes().isNotEmpty() }
        captured
    }

    @Test
    fun bar_passesCompactTrue_atNarrowWidth() = runComposeUiTest {
        assertEquals(true, capturedCompactAt(this, widthDp = 360), "the bar is compact at 360dp")
    }

    @Test
    fun bar_passesCompactFalse_atWideWidth() = runComposeUiTest {
        assertEquals(false, capturedCompactAt(this, widthDp = 600), "the bar is not compact at 600dp")
    }
}
