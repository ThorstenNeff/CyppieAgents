package com.tneff.cyppieagents.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.RuntimeState
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-262 Teil 2 — the per-project runtime-session indicator in the switcher ▾-menu (UIUX spec `ade1e55`, §9
 * invariants 7–11). It binds 1:1 to the server-authoritative [Project.runtimeState] (`HOT|BACKGROUND|SUSPENDED`,
 * the CYP-249 contract), NOT to the active pointer: HOT (and a pre-Push-3 default payload) → NO indicator
 * (fail-safe); BACKGROUND → INFO "Running in the background"; SUSPENDED → INFO "Suspended — resumes on open".
 * jvmTest render locale = EN. a11y (`a11y_project_session`) carries the state for screenreader parity.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectSwitcherSessionIndicatorTest {

    private fun ComposeUiTest.openMenuWith(projects: List<Project>, active: String = "default") {
        setContent {
            MaterialTheme {
                val vm = remember { ProjectViewModel(StubProjectRepository(projects, active), editable = true) }
                ProjectSwitcherBar(vm)
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.MENU).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ProjectTags.MENU).performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.MENU).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The a11y state text on a project's session indicator (`a11y_project_session` = "Project session: <state>"). */
    private fun ComposeUiTest.sessionA11y(id: String): String? =
        onNodeWithTag(ProjectTags.itemSession(id), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    @Test
    fun background_showsRunningInBackground_infoIndicator_withStateA11y() = runComposeUiTest {
        openMenuWith(listOf(Project("default", "Default"), Project("beta", "Beta", RuntimeState.BACKGROUND)))
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.itemSession("beta"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.itemSession("beta"), useUnmergedTree = true).assertExists()
        onNodeWithText("Running in the background", substring = true, useUnmergedTree = true).assertExists()
        assertTrue(sessionA11y("beta")?.contains("Running in the background") == true, "§9-8 a11y carries the state")
    }

    @Test
    fun suspended_showsSuspendedResumesOnOpen_infoIndicator_withStateA11y() = runComposeUiTest {
        openMenuWith(listOf(Project("default", "Default"), Project("gamma", "Gamma", RuntimeState.SUSPENDED)))
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.itemSession("gamma"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.itemSession("gamma"), useUnmergedTree = true).assertExists()
        onNodeWithText("Suspended", substring = true, useUnmergedTree = true).assertExists()
        // §9-9: the reassurance ("resumes on open"), no LRU/K=3 jargon.
        assertTrue(sessionA11y("gamma")?.contains("Suspended — resumes on open") == true, "§9-9 a11y carries the state")
    }

    /** §9-10 (HOT no session indicator) + §9-11 (fail-safe default): neither the active HOT project nor a non-active
     *  HOT project carries a session indicator — only BACKGROUND/SUSPENDED do. */
    @Test
    fun hot_activeOrNot_showsNoSessionIndicator() = runComposeUiTest {
        openMenuWith(
            listOf(Project("default", "Default"), Project("delta", "Delta", RuntimeState.HOT)),
            active = "default",
        )
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.item("delta")).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.itemSession("default"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ProjectTags.itemSession("delta"), useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * §9-11 authoritative source: the indicator reflects each project's own [Project.runtimeState], NOT the active
     * pointer. The ACTIVE project ("default") is HOT → no indicator; a NON-active BACKGROUND and a NON-active
     * SUSPENDED each show their own — and the two are distinct in a11y (§9-10). Guards against deriving state from
     * activeProjectId (which would give every non-active project the same indicator, or none).
     */
    @Test
    fun perProjectRuntimeState_notActivePointer_distinctBackgroundVsSuspended() = runComposeUiTest {
        openMenuWith(
            listOf(
                Project("default", "Default"), // HOT (active) → no indicator
                Project("beta", "Beta", RuntimeState.BACKGROUND),
                Project("gamma", "Gamma", RuntimeState.SUSPENDED),
            ),
            active = "default",
        )
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.itemSession("gamma"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(ProjectTags.itemSession("default"), useUnmergedTree = true).assertDoesNotExist() // active HOT
        val bg = sessionA11y("beta")
        val susp = sessionA11y("gamma")
        assertTrue(bg?.contains("Running in the background") == true)
        assertTrue(susp?.contains("Suspended") == true)
        assertNotEquals(bg, susp, "§9-10 BACKGROUND and SUSPENDED are distinct in a11y")
    }

    /** §9-7 no error appearance: the session indicators use the INFO tone (glyph "i"), never the ERROR glyph "✕". */
    @Test
    fun sessionIndicators_useInfoTone_neverErrorGlyph() = runComposeUiTest {
        openMenuWith(
            listOf(
                Project("default", "Default"),
                Project("beta", "Beta", RuntimeState.BACKGROUND),
                Project("gamma", "Gamma", RuntimeState.SUSPENDED),
            ),
        )
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(ProjectTags.itemSession("beta"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The INFO glyph is present; the ERROR glyph "✕" appears nowhere (a tone→ERROR regression would add it).
        assertTrue(
            onAllNodesWithText("i", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            onAllNodesWithText("✕", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            "§9-7 BACKGROUND/SUSPENDED are INFO, never an error tone",
        )
    }
}
