package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

/**
 * CYP-288 A4/ACL — a FAILED non-gated matrix load (channels/agents/entries) must render the honest error+retry
 * surface (shared LoadErrorRetry), NOT the "empty matrix" state. Sweep-#4 class A: load() swallowed all three to
 * empty with no error field, so a failed matrix was indistinguishable from a genuinely-empty one (and the
 * DISCONNECTED banner does not cover it — the WS can be live while REST fails). The operator-only members roster
 * stays fail-closed-to-empty by design (CYP-189 Invariante E) and is NOT part of loadError.
 *
 * Unconfined scope → synchronous load → deterministic waitForIdle (no wall-clock waitUntil, the CYP-271 class).
 * Mutation proof: restore the swallow (loadError=false) → the failure test REDs (empty matrix shows).
 */
@OptIn(ExperimentalTestApi::class)
class AclLoadErrorSurfaceTest {

    private class IdleAclSource : AclLiveSource {
        override fun events(): Flow<AclLiveEvent> = flow { awaitCancellation() }
    }

    private class FailingAclApi : AclApi {
        override suspend fun channels(): List<Channel> = throw RuntimeException("boom")
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> = emptyList()
        override suspend fun setAcl(entry: AclEntry): AclEntry = entry
    }

    private class EmptyAclApi : AclApi {
        override suspend fun channels(): List<Channel> = emptyList()
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> = emptyList()
        override suspend fun setAcl(entry: AclEntry): AclEntry = entry
    }

    /** Fails once (→ error), then returns a 1×1 matrix → proves Retry re-invokes the load. */
    private class FlakyAclApi : AclApi {
        var calls = 0
        override suspend fun channels(): List<Channel> {
            calls += 1
            if (calls == 1) throw RuntimeException("boom")
            return listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        }
        override suspend fun agents(): List<Agent> = listOf(Agent("po", "Product Owner", Role.PO, "po"))
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> = emptyList()
        override suspend fun setAcl(entry: AclEntry): AclEntry = entry
    }

    private fun vm(api: AclApi) = AclViewModel(api, IdleAclSource(), editable = true, scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun matrixLoadFailure_showsErrorAndRetry_notEmpty() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(remember { vm(FailingAclApi()) }) } } }
        waitForIdle()
        onNodeWithTag(AclMatrixTags.ERROR).assertExists()
        onNodeWithTag(AclMatrixTags.ERROR_RETRY).assertExists()
        onNodeWithTag(AclMatrixTags.EMPTY).assertDoesNotExist() // error beats empty — the fix
    }

    @Test
    fun genuinelyEmpty_showsEmpty_notError() = runComposeUiTest {
        // Non-vacuous contrast: a SUCCESSFUL empty matrix still shows the plain empty state, never the error.
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(remember { vm(EmptyAclApi()) }) } } }
        waitForIdle()
        onNodeWithTag(AclMatrixTags.EMPTY).assertExists()
        onNodeWithTag(AclMatrixTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun retry_reinvokesLoad_recovers() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(900.dp)) { AclPanel(remember { vm(FlakyAclApi()) }) } } }
        waitForIdle()
        onNodeWithTag(AclMatrixTags.ERROR).assertExists()
        onNodeWithTag(AclMatrixTags.ERROR_RETRY).performClick() // re-invokes the load; the 2nd load succeeds
        waitForIdle()
        onNodeWithTag(AclMatrixTags.ERROR).assertDoesNotExist()
        onNodeWithTag(AclMatrixTags.EMPTY).assertDoesNotExist() // neither error nor empty → the matrix recovered
    }
}
