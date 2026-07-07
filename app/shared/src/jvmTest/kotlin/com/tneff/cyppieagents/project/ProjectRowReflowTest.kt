package com.tneff.cyppieagents.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Project
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-282 — the ProjectRow mirrors the CYP-156 AgentRow reflow: below PANE_COLLAPSE_WIDTH the name + the two
 * wide German action buttons ("Umbenennen"/"Löschen") split into a 2-line layout (identity line 1, actions
 * line 2) so the name keeps full width instead of being squeezed to a few chars. Wide keeps the single row.
 *
 * The buttons stay on-screen in BOTH layouts (the name has weight(1f)), so `assertIsDisplayed` would be
 * vacuous — this asserts the POSITION: at narrow the delete action reflows BELOW the identity line, at wide it
 * sits beside it. Mutation proof: drop the reflow (always the single Row) → the narrow assertion REDs.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectRowReflowTest {

    // A long name so the single-row layout genuinely squeezes it (the defect); the reflow gives it line 1.
    private val proj = Project("default", "A deliberately long project name that a narrow single row squeezes")

    private fun ComposeUiTest.renderAt(widthDp: Int) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(widthDp.dp).height(500.dp)) {
                    val vm = remember { ProjectViewModel(StubProjectRepository(listOf(proj), "default"), editable = true) }
                    ProjectManagementPanel(vm)
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.rowDelete("default")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun narrowWidth_deleteReflowsBelowTheIdentityLine() = runComposeUiTest {
        renderAt(340) // < PANE_COLLAPSE_WIDTH (600dp)
        val identityBottom = onNodeWithTag(ProjectTags.rowActive("default")).getBoundsInRoot().bottom
        val deleteTop = onNodeWithTag(ProjectTags.rowDelete("default")).getBoundsInRoot().top
        assertTrue(deleteTop >= identityBottom, "narrow: delete ($deleteTop) reflows BELOW the identity line ($identityBottom)")
    }

    @Test
    fun wideWidth_deleteStaysBesideTheIdentity() = runComposeUiTest {
        renderAt(720) // >= PANE_COLLAPSE_WIDTH → single row
        val identityBottom = onNodeWithTag(ProjectTags.rowActive("default")).getBoundsInRoot().bottom
        val deleteTop = onNodeWithTag(ProjectTags.rowDelete("default")).getBoundsInRoot().top
        assertTrue(deleteTop < identityBottom, "wide: delete ($deleteTop) stays on the identity line ($identityBottom)")
    }
}
