package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlin.test.Test

/**
 * CYP-317 — the flexible ACL matrix: EVERY agent×channel cell is grantable (including non-members), honestly
 * coupled to "Grant ⇒ Membership". Teeth:
 *  - a non-member cell is grantable (switches + NON_MEMBER affordance marker — the §7 QA both-nodes contract);
 *  - honesty (§9-3/§9-4): a not-yet-granted non-member NEVER shows the "enforced" marker (no fake permission);
 *  - the full §3 flow: granting a non-member syncs membership server-side → the cell becomes an enforced member.
 */
@OptIn(ExperimentalTestApi::class)
class AclFlexibleGrantTest {

    /**
     * Models the CYP-317 backend: `PUT /api/acl` on a NON-member atomically adds channel membership together with
     * the entry, echoing BOTH `ChannelsChanged` (membership) and `EntryChanged` (grant) over the live source — so
     * the cell transitions non-member → member+enforced only after the real echo (never faked). `backend` starts a
     * non-member of `po-frontend`.
     */
    private class MembershipSyncHub : AclApi, AclLiveSource {
        private val channels = mutableListOf(
            Channel("po-frontend", "PO ⇄ Frontend", ChannelKind.HUB, listOf("po", "frontend", "operator")),
        )
        private val agents = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
            Agent("backend", "Backend", Role.WORKER, "backend"),
        )
        private val entries = mutableListOf<AclEntry>()
        private val broadcasts = MutableSharedFlow<AclLiveEvent>(extraBufferCapacity = 64)

        override suspend fun channels(): List<Channel> = channels.toList()
        override suspend fun agents(): List<Agent> = agents
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> =
            entries.filter { (channelId == null || it.channelId == channelId) && (agentId == null || it.agentId == agentId) }

        override suspend fun setAcl(entry: AclEntry): AclEntry {
            // Atomic membership sync: a grant to a non-member adds them to the channel (the CYP-317 contract),
            // echoed as ChannelsChanged so the cell's `isMember` flips to true — the grant is now enforceable.
            val idx = channels.indexOfFirst { it.id == entry.channelId }
            if (idx >= 0 && entry.agentId !in channels[idx].members) {
                channels[idx] = channels[idx].copy(members = channels[idx].members + entry.agentId)
                broadcasts.tryEmit(AclLiveEvent.ChannelsChanged(channels.toList()))
            }
            entries.removeAll { it.channelId == entry.channelId && it.agentId == entry.agentId }
            entries.add(entry)
            broadcasts.tryEmit(AclLiveEvent.EntryChanged(entry)) // grant echo → pending clears, cell reconciles
            return entry
        }

        override fun events(): Flow<AclLiveEvent> = broadcasts.onStart { emit(AclLiveEvent.Connected) }
    }

    @Test
    fun nonMemberCell_isGrantable_butNeverFakesEnforced() = runComposeUiTest {
        // StubAclHub: `backend` is a non-member of `po-frontend`, no entry. The cell is grantable (marker + switch)
        // yet must NOT show the ENFORCED "durchgesetzt" marker — that would claim a settled grant it doesn't have.
        val hub = StubAclHub()
        val vm = AclViewModel(hub, hub)
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }

        // Grantable: the NON_MEMBER affordance marker AND the read switch are both present (§7 QA both-nodes).
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", "backend", CellQualifier.NON_MEMBER), useUnmergedTree = true).assertExists()
        onNodeWithTag(AclMatrixTags.read("po-frontend", "backend"), useUnmergedTree = true).assertExists()
        // Honesty (§9-3/§9-4): NO "enforced" marker on a not-yet-granted non-member. Mutation: ungate the enforced
        // dot (`cell.isMember ->` back to `else ->`) → the non-member renders ENFORCED → RED.
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", "backend", CellQualifier.ENFORCED), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun grantNonMember_syncsMembership_cellBecomesEnforcedMember() = runComposeUiTest {
        // The §3 flow end-to-end: toggle a non-member's read → the (modeled) backend adds membership + the grant →
        // the cell becomes a MEMBER cell whose read switch is ON (enforced). Never faked before the echo.
        val hub = MembershipSyncHub()
        val vm = AclViewModel(hub, hub)
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(vm) } } }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }

        // Non-member `backend` on `po-frontend`: grantable switch present, off.
        onNodeWithTag(AclMatrixTags.read("po-frontend", "backend"), useUnmergedTree = true).assertIsOff()
        onNodeWithTag(AclMatrixTags.read("po-frontend", "backend"), useUnmergedTree = true).performClick()
        // After the atomic membership+grant echo, the cell is an enforced MEMBER cell with read ON.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag(AclMatrixTags.cellQualifier("po-frontend", "backend", CellQualifier.ENFORCED)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(AclMatrixTags.read("po-frontend", "backend"), useUnmergedTree = true).assertIsOn()
        // The NON_MEMBER affordance is gone — it's a member now (honest transition).
        onNodeWithTag(AclMatrixTags.cellQualifier("po-frontend", "backend", CellQualifier.NON_MEMBER), useUnmergedTree = true).assertDoesNotExist()
    }
}
