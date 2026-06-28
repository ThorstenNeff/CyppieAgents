package com.tneff.cyppieagents.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Project
import kotlin.test.Test

/**
 * CYP-91 render gate: the management panel lists projects, shows the operator gate honestly (read-only +
 * gate hint without a token), and surfaces the delete-safety guardrails **before** the action — the active
 * and last project have their delete disabled with an inline reason. testTags exactly per
 * `docs/design/project-management-tags.md`.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectManagementPanelTest {

    private val default = Project("default", "Default")
    private val other = Project("other", "Other")

    @Test
    fun operator_listsProjects_activeDeleteBlocked_inlineReason() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    ProjectViewModel(StubProjectRepository(listOf(default, other), "default"), editable = true)
                }
                ProjectManagementPanel(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.row("default")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.ADD).assertIsEnabled()
        onNodeWithTag(ProjectTags.GATE_HINT).assertDoesNotExist()
        // Active project: delete disabled + inline blocked reason BEFORE the action.
        onNodeWithTag(ProjectTags.rowDelete("default")).assertIsNotEnabled()
        onNodeWithTag(ProjectTags.rowDeleteBlocked("default")).assertExists()
        onNodeWithTag(ProjectTags.rowActive("default")).assertExists()
        // Non-active project is deletable.
        onNodeWithTag(ProjectTags.rowDelete("other")).assertIsEnabled()
    }

    @Test
    fun singleProject_lastDeleteBlocked() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    ProjectViewModel(StubProjectRepository(listOf(default), "default"), editable = true)
                }
                ProjectManagementPanel(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.row("default")).fetchSemanticsNodes().isNotEmpty()
        }
        // The single (also active) project is unremovable — last_project blocked, inline reason shown.
        onNodeWithTag(ProjectTags.rowDelete("default")).assertIsNotEnabled()
        onNodeWithTag(ProjectTags.rowDeleteBlocked("default")).assertExists()
    }

    @Test
    fun noOperator_gateHint_mutationsDisabled() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    ProjectViewModel(StubProjectRepository(listOf(default, other), "default"), editable = false)
                }
                ProjectManagementPanel(vm)
            }
        }
        onNodeWithTag(ProjectTags.GATE_HINT).assertExists()
        onNodeWithTag(ProjectTags.ADD).assertIsNotEnabled()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.row("other")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.rowRename("other")).assertIsNotEnabled()
        onNodeWithTag(ProjectTags.rowDelete("other")).assertIsNotEnabled()
    }

    @Test
    fun deleteDialog_nonActive_showsConsequences_andWorktreeWarningOnDeletePath() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember {
                    ProjectViewModel(StubProjectRepository(listOf(default, other), "default"), editable = true)
                }
                ProjectManagementPanel(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.rowDelete("other")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.rowDelete("other")).performClick()
        onNodeWithTag(ProjectTags.DELETE_DIALOG).assertExists()
        // Guaranteed consequences (categories) + keep-default. No counts slot (Fast-Follow, not invented).
        onNodeWithTag(ProjectTags.DELETE_CONSEQUENCES).assertExists()
        onNodeWithTag(ProjectTags.DELETE_COUNTS).assertDoesNotExist()
        onNodeWithTag(ProjectTags.DELETE_WORKTREE_WARNING).assertDoesNotExist() // keep = no data-loss warning
        // Switch to the delete-worktrees path → the data-loss warning appears.
        onNodeWithTag(ProjectTags.DELETE_WORKTREE_DELETE).performClick()
        onNodeWithTag(ProjectTags.DELETE_WORKTREE_WARNING).assertExists()
    }
}
