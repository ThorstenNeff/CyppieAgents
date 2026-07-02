package com.tneff.cyppieagents.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.WorkspaceMember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-186 (roster fold) — the OPERATOR roster panel renders the container + a row per member with a
 * short, non-identifying label + the tier. The tag scope is the (stable) identityId; the DISPLAY is
 * shortened — never the raw/long id, never an email.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceRosterPanelTest {

    private val members = listOf(
        WorkspaceMember(identityId = "aaaaaaaa-1111-2222-3333", tier = "OPERATOR"),
        WorkspaceMember(identityId = "bbbbbbbb-4444-5555-6666", tier = "MEMBER", displayName = "Bob"),
    )

    private fun vm() = WorkspaceRosterViewModel(
        StubWorkspaceRepository(members),
        scope = CoroutineScope(Dispatchers.Unconfined), // init load resolves synchronously
    )

    @Test
    fun roster_rendersContainer_andRowPerMember_withTierLabels() = runComposeUiTest {
        setContent { MaterialTheme { WorkspaceRosterPanel(vm()) } }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(WorkspaceTags.MEMBERS).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(WorkspaceTags.MEMBERS).assertExists()
        onNodeWithTag(WorkspaceTags.member("aaaaaaaa-1111-2222-3333")).assertExists()
        onNodeWithTag(WorkspaceTags.member("bbbbbbbb-4444-5555-6666")).assertExists()
        onNodeWithTag(WorkspaceTags.memberRole("aaaaaaaa-1111-2222-3333"), useUnmergedTree = true).assertExists()
        onNodeWithTag(WorkspaceTags.memberRole("bbbbbbbb-4444-5555-6666"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun memberLabel_prefersDisplayName_elseShortId_neverRawLongId() {
        assertEquals("Bob", memberLabel(WorkspaceMember("bbbbbbbb-4444-5555-6666", "MEMBER", "Bob")))
        // displayName null (BE3a today) → shortened id, NOT the raw 36-char UUID.
        assertEquals("aaaaaaaa", memberLabel(WorkspaceMember("aaaaaaaa-1111-2222-3333", "OPERATOR")))
        assertEquals(8, shortId("aaaaaaaa-1111-2222-3333").length)
        // blank displayName also falls back to the short id (never renders an empty label).
        assertEquals("cccccccc", memberLabel(WorkspaceMember("cccccccc-7777", "MEMBER", "   ")))
    }
}
