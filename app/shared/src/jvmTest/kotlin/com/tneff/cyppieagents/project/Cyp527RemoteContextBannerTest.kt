package com.tneff.cyppieagents.project

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.workspace.RemoteContextBanner
import com.tneff.cyppieagents.workspace.WorkspaceTags
import kotlin.test.Test

/**
 * CYP-527 — the render + **present-iff (G2)** teeth of the remote-context WARN banner:
 *  - render: the `workspace.remoteContext` node exists, the `▲` glyph is a **separate** node (WCAG 1.4.1, colour
 *    never sole carrier), and the copy carries the hub name + the CR3 disclosure;
 *  - **G2** present ⇔ the caller provides the banner: `ProjectSwitcherBar` shows `workspace.remoteContext` when the
 *    `remoteContextBanner` slot is filled (the `AgentShell` wires it iff `remoteContext != null`), and it is
 *    **absent** with the default-empty slot (the LOCAL / not-connected path).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp527RemoteContextBannerTest {

    private val default = Project("default", "Default")

    @Test
    fun banner_rendersTag_glyphSeparate_andHubName() = runComposeUiTest {
        setContent { MaterialTheme { RemoteContextBanner(hubName = "Mein Hub") } }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
        onNodeWithText("▲", useUnmergedTree = true).assertExists() // separate glyph node, not folded into the copy
        onNodeWithText("Mein Hub", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun g2_present_whenSlotFilled() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProjectViewModel(StubProjectRepository(listOf(default), "default"), editable = true) }
                ProjectSwitcherBar(vm, remoteContextBanner = { RemoteContextBanner(hubName = "Mein Hub") })
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.ACTIVE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertExists()
    }

    @Test
    fun g2_absent_whenSlotDefault_localGuard() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val vm = remember { ProjectViewModel(StubProjectRepository(listOf(default), "default"), editable = true) }
                ProjectSwitcherBar(vm) // default-empty remoteContextBanner slot = the LOCAL / not-connected path
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(ProjectTags.ACTIVE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(WorkspaceTags.REMOTE_CONTEXT, useUnmergedTree = true).assertDoesNotExist()
    }
}
