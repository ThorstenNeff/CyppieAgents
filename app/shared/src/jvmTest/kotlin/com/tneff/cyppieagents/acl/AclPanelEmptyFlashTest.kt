package com.tneff.cyppieagents.acl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test

/**
 * CYP-276 (CYP-270 class) — the ACL "no data" message must NOT flash during the async load window (cold open /
 * project switch); only a SETTLED-empty matrix shows it. [GatedAclHub.channels] suspends so the VM stays
 * `loading = true`, proving AclPanel gates `acl_empty` on `!loading`.
 *
 * Mutation proof: drop the `!state.loading &&` guard at AclPanel:116 → `acl_empty` renders WHILE loading → the
 * first assertion of [aclEmpty_neverFlashesDuringLoad_thenShowsWhenSettledEmpty] REDs.
 */
@OptIn(ExperimentalTestApi::class)
class AclPanelEmptyFlashTest {

    /** `channels()` suspends on [gate] → the VM stays `loading = true` until released; [data] picks empty vs a matrix. */
    private class GatedAclHub(val gate: CompletableDeferred<Unit>, val data: Boolean) : AclApi, AclLiveSource {
        override suspend fun channels(): List<Channel> {
            gate.await()
            return if (data) listOf(Channel("po-fe", "po-fe", ChannelKind.DIRECT, listOf("po", "fe"))) else emptyList()
        }
        override suspend fun agents(): List<Agent> = if (data) {
            listOf(Agent("po", "PO", Role.PO, "po", AgentRunState.RUNNING), Agent("fe", "FE", Role.WORKER, "fe", AgentRunState.RUNNING))
        } else {
            emptyList()
        }
        override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> = emptyList()
        override suspend fun setAcl(entry: AclEntry): AclEntry = entry
        override fun events(): Flow<AclLiveEvent> = emptyFlow()
    }

    @Test
    fun aclEmpty_neverFlashesDuringLoad_thenShowsWhenSettledEmpty() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp)) {
                    val hub = remember { GatedAclHub(gate, data = false) }
                    AclPanel(remember { AclViewModel(hub, hub) })
                }
            }
        }
        waitForIdle()
        // Loading (gate not released) → the "no channels/participants" message must NOT flash.
        onNodeWithTag(AclMatrixTags.EMPTY).assertDoesNotExist()
        // Release → settles empty → the empty message now shows (non-vacuous: it CAN show).
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.EMPTY).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AclMatrixTags.EMPTY).assertExists()
    }

    @Test
    fun aclEmpty_neverShows_whenLoadYieldsMatrix() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp)) {
                    val hub = remember { GatedAclHub(gate, data = true) }
                    AclPanel(remember { AclViewModel(hub, hub) })
                }
            }
        }
        waitForIdle()
        onNodeWithTag(AclMatrixTags.EMPTY).assertDoesNotExist() // during load
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(AclMatrixTags.GRID).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(AclMatrixTags.EMPTY).assertDoesNotExist() // matrix present → never
    }
}
