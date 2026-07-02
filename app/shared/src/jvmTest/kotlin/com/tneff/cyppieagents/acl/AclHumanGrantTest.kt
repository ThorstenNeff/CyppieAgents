package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.WorkspaceMember
import com.tneff.cyppieagents.workspace.StubWorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-189 — the Operator Grant-UI (Human `canWrite` per channel) extension of the ACL matrix. Disclosure
 * invariants (spec §10), teethed: the operator-only **enumeration** seam (Invariante E — no roster leak to a
 * non-operator), humans grantable on ALL channels (no membership `—` suppression), the human toggle round-trips
 * through the hub echo with NO PO-lockout, and the ghost-channel 404 → the honest non-retryable `acl_channel_gone`.
 */
@OptIn(ExperimentalTestApi::class)
class AclHumanGrantTest {

    private val alice = "iiii-1111-2222" // a roster identityId; NOT a member of any seeded channel
    private fun roster() = StubWorkspaceRepository(listOf(WorkspaceMember(alice, "MEMBER", "Alice")))

    @Test
    fun operator_rendersHumanBand_grantableOnEveryChannel_notNonMember() = runComposeUiTest {
        val hub = StubAclHub()
        val vm = AclViewModel(hub, hub, editable = true, workspaceRepository = roster())
        setContent { MaterialTheme { Box(Modifier.width(1100.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.HUMANS_GROUP).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AclMatrixTags.HUMANS_GROUP).assertExists()
        onNodeWithTag(AclMatrixTags.humanMarker(alice), useUnmergedTree = true).assertExists()
        // Grantable on `po-frontend` — a channel Alice is NOT a member of: full R/W toggles, NO `—` suppression (§3.1).
        onNodeWithTag(AclMatrixTags.read("po-frontend", alice), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.write("po-frontend", alice), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", alice, CellQualifier.NON_MEMBER), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun nonOperator_humanBand_structurallyAbsent_noRosterLeak() = runComposeUiTest {
        val hub = StubAclHub()
        // ⭐ Invariante E: even with a roster repo that HAS members, a non-operator (editable=false) must NEVER
        // render the human band — QA asserts the no-leak by the ABSENCE of these nodes (structural, not disabled).
        val vm = AclViewModel(hub, hub, editable = false, workspaceRepository = roster())
        setContent { MaterialTheme { Box(Modifier.width(1100.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AclMatrixTags.HUMANS_GROUP).assertDoesNotExist()
        onNodeWithTag(AclMatrixTags.humanMarker(alice), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AclMatrixTags.read("po-frontend", alice), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AclMatrixTags.write("po-frontend", alice), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun humanWriteToggle_roundTripsThroughHubEcho_toEnforced_noLockoutDialog() = runComposeUiTest {
        val hub = StubAclHub()
        val vm = AclViewModel(hub, hub, editable = true, workspaceRepository = roster())
        setContent { MaterialTheme { Box(Modifier.width(1100.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.write("po-frontend", alice)).fetchSemanticsNodes().isNotEmpty() }

        onNodeWithTag(AclMatrixTags.write("po-frontend", alice), useUnmergedTree = true).performClick()
        // The hub echoes AclEvent → the human cell settles to `enforced`. A human NEVER trips the PO-lockout dialog.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AclMatrixTags.cellQualifier("po-frontend", alice, CellQualifier.ENFORCED)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", alice, CellQualifier.ENFORCED), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.LOCKOUT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun humanToggle_ghostChannel404_mapsToChannelGone_distinctFromChangeFailed() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val api = object : AclApi {
                override suspend fun channels() = StubAclHub.DEFAULT_CHANNELS
                override suspend fun agents() = StubAclHub.DEFAULT_AGENTS
                override suspend fun acl(channelId: String?, agentId: String?) = emptyList<AclEntry>()
                override suspend fun setAcl(entry: AclEntry): AclEntry =
                    throw AclHttpException(404, """{"error":{"code":"channel_gone"}}""") // built grant-hardening
            }
            val live = object : AclLiveSource { override fun events() = emptyFlow<AclLiveEvent>() }
            val vm = AclViewModel(api, live, editable = true, workspaceRepository = roster(), scope = scope)
            vm.toggleWrite("po-frontend", alice)
            // ⭐ 404 → the honest, NON-retryable notice — distinct from the retryable acl_change_failed.
            assertEquals("acl_channel_gone", vm.state.value.notice)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun operatorTierMember_omittedFromGrantableHumanBand() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val hub = StubAclHub()
            val roster = StubWorkspaceRepository(listOf(
                WorkspaceMember("mmmm-member", "MEMBER", "Alice"),
                WorkspaceMember("oooo-operator", "OPERATOR", "Owner"),
            ))
            val vm = AclViewModel(hub, hub, editable = true, workspaceRepository = roster, scope = scope)
            val members = vm.state.value.members
            // §9.4: MEMBER-tier grantable; OPERATOR-tier (self + co-operators) omitted — a self-grant is a no-op.
            assertTrue(members.any { it.identityId == "mmmm-member" })
            assertTrue(members.none { it.identityId == "oooo-operator" }, "operator-tier omitted from the grantable band")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nonOperator_neverFetchesRoster_membersEmpty() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val hub = StubAclHub()
            // Invariante E, point 1: a non-operator never requests the roster → members stays empty (no fetch).
            val vm = AclViewModel(hub, hub, editable = false, workspaceRepository = roster(), scope = scope)
            assertTrue(vm.state.value.members.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
