package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorkspaceMember
import com.tneff.cyppieagents.workspace.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

/**
 * CYP-295 (security-adjacent, CYP-288 A4 follow-up) — the operator-only members-roster fetch is fail-closed and
 * DELIBERATELY EXCLUDED from the CYP-288 `loadError` surface: a `members()` failure must NEVER show the error
 * state, because an "operator members load failed" surface would DISCLOSE the roster's existence (CYP-189
 * Invariante E, non-disclosure). Only the non-gated reads (channels/agents/entries) drive `loadError`.
 *
 * Pins the invariant: an operator VM whose members() throws (but whose channels/agents load fine) renders the
 * MATRIX, never the error surface. Mutation proof: fold the members failure into `loadError` → this REDs.
 */
@OptIn(ExperimentalTestApi::class)
class AclMembersFailClosedTest {

    private class IdleAclSource : AclLiveSource {
        override fun events(): Flow<AclLiveEvent> = flow { awaitCancellation() }
    }

    /** Non-gated reads all succeed → the matrix renders; only the members roster fetch will fail. */
    private class ValidAclApi : AclApi {
        override suspend fun channels(): List<Channel> = listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents(): List<Agent> = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> = emptyList()
        override suspend fun setAcl(entry: AclEntry): AclEntry = entry
    }

    /** The operator-only roster fetch throws — fail-closed to empty, NEVER the error surface (Invariante E). */
    private class FailingWorkspaceRepository : WorkspaceRepository {
        override suspend fun members(): List<WorkspaceMember> = throw RuntimeException("roster boom")
    }

    @Test
    fun membersLoadFailure_doesNotShowError_matrixStillRenders() = runComposeUiTest {
        val vm = AclViewModel(
            ValidAclApi(),
            IdleAclSource(),
            editable = true, // operator → members() IS fetched
            workspaceRepository = FailingWorkspaceRepository(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(remember { vm }) } } }
        waitForIdle()
        onNodeWithTag(AclMatrixTags.ERROR).assertDoesNotExist()    // a members failure must NOT trigger the error surface
        onNodeWithTag(AclMatrixTags.EMPTY).assertDoesNotExist()     // channels/agents present → not empty either
        onNodeWithTag(AclMatrixTags.GRID).assertExists()            // the matrix renders unaffected
    }
}
