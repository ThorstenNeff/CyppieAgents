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
import com.tneff.cyppieagents.auth.UserTier
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.workspace.WorkspaceTags
import kotlin.test.Test

/**
 * CYP-92 render gate: the top-level switcher bar shows the active project (form marker + label) and the
 * scope hint, opens the switch menu (non-destructive — the switch hint says so), and hosts the management
 * overlay. Switching is operator-gated. testTags exactly per `docs/design/project-management-tags.md`.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectSwitcherBarTest {

    private val default = Project("default", "Default")
    private val other = Project("other", "Other")

    private fun setBar(test: androidx.compose.ui.test.ComposeUiTest, editable: Boolean) = test.run {
        setContent {
            MaterialTheme {
                val vm = remember {
                    ProjectViewModel(StubProjectRepository(listOf(default, other), "default"), editable = editable)
                }
                ProjectSwitcherBar(vm)
            }
        }
    }

    @Test
    fun bar_showsActive_scopeHint_andOpensMenu() = runComposeUiTest {
        setBar(this, editable = true)
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.ACTIVE).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.BAR).assertExists()
        onNodeWithTag(ProjectTags.ACTIVE).assertExists()
        onNodeWithTag(ProjectTags.SCOPE_HINT).assertExists()
        // Open the switch menu → items + the non-destructive switch hint appear.
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.item("other")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.SWITCH_HINT).assertExists()
        // Active marker lives inside the (merged) DropdownMenuItem → query the unmerged tree.
        onNodeWithTag(ProjectTags.itemActive("default"), useUnmergedTree = true).assertExists()
        onNodeWithTag(ProjectTags.item("other")).assertIsEnabled() // switchable target (operator)
    }

    @Test
    fun manage_opensManagementOverlay() = runComposeUiTest {
        setBar(this, editable = true)
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.MENU).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.MANAGE).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.MANAGE).performClick()
        // The overlay hosts the management panel (CYP-91).
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.PANEL).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.PANEL).assertExists()
    }

    @Test
    fun switch_disabledWithoutOperator() = runComposeUiTest {
        setBar(this, editable = false)
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.MENU).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.item("other")).fetchSemanticsNodes().isNotEmpty()
        }
        // Switching is a server-gated mutation — without an operator token the target is disabled.
        onNodeWithTag(ProjectTags.item("other")).assertIsNotEnabled()
    }

    // --- CYP-186: persistent role indicator (spec §3.1) ---

    private fun setBarTier(
        test: androidx.compose.ui.test.ComposeUiTest,
        tier: UserTier,
        operatorName: String? = null,
    ) = test.run {
        setContent {
            MaterialTheme {
                val vm = remember {
                    ProjectViewModel(
                        StubProjectRepository(listOf(default, other), "default"),
                        editable = tier == UserTier.OPERATOR,
                    )
                }
                ProjectSwitcherBar(vm, tier = tier, operatorName = operatorName)
            }
        }
    }

    @Test
    fun roleIndicator_operator_shownWithoutOperatorNameLine() = runComposeUiTest {
        setBarTier(this, UserTier.OPERATOR, operatorName = "Alice")
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WorkspaceTags.ROLE_INDICATOR).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WorkspaceTags.ROLE_INDICATOR).assertExists()
        // An operator never sees the "Operator: …" line (that's the MEMBER's who-to-ask cue).
        onNodeWithTag(WorkspaceTags.OPERATOR_NAME).assertDoesNotExist()
    }

    @Test
    fun roleIndicator_member_withOperatorName_showsBoth() = runComposeUiTest {
        setBarTier(this, UserTier.MEMBER, operatorName = "Alice")
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WorkspaceTags.ROLE_INDICATOR).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WorkspaceTags.ROLE_INDICATOR).assertExists()
        onNodeWithTag(WorkspaceTags.OPERATOR_NAME).assertExists() // MEMBER learns who to ask
    }

    @Test
    fun roleIndicator_member_noOperatorName_omitsNameLine() = runComposeUiTest {
        // BE1 not yet delivering the operator name → omit the line entirely (never a placeholder/contact dump).
        setBarTier(this, UserTier.MEMBER, operatorName = null)
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WorkspaceTags.ROLE_INDICATOR).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WorkspaceTags.ROLE_INDICATOR).assertExists()
        onNodeWithTag(WorkspaceTags.OPERATOR_NAME).assertDoesNotExist()
    }
}
