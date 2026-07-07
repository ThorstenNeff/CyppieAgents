package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

/**
 * CYP-288 A2/Comm — a FAILED channel-list load and a FAILED history load must each render the honest error+retry
 * surface (shared [com.tneff.cyppieagents.ui.LoadErrorRetry]), NOT the "no channels" / "empty channel" empty
 * state (Sweep-#4 class A: both loads previously swallowed to empty with no error field). Error beats empty.
 *
 * Mutation proof: revert either panel branch to the plain empty guard (drop the error branch), or restore the
 * `getOrDefault(emptyList())` swallow in the VM → the matching error test REDs (the empty state shows). The
 * genuinely-empty contrast keeps the branch non-vacuous.
 */
@OptIn(ExperimentalTestApi::class)
class CommErrorSurfaceTest {

    /** Live stream that never completes → collectLive just suspends (no reconnect churn during the test). */
    private class IdleSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow { awaitCancellation() }
    }

    private class FailingChannelsApi : CommApi {
        override suspend fun channels(): List<Channel> = throw RuntimeException("boom")
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message = throw RuntimeException("no")
    }

    /** Channels load fine (1 channel → auto-selected), but the history query fails → historyError. */
    private class FailingHistoryApi : CommApi {
        override suspend fun channels(): List<Channel> = listOf(Channel("c1", "C1", ChannelKind.HUB, listOf("po")))
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = throw RuntimeException("boom")
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message = throw RuntimeException("no")
    }

    private class EmptyApi : CommApi {
        override suspend fun channels(): List<Channel> = emptyList()
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message = throw RuntimeException("no")
    }

    // CYP-288 de-flake (CYP-271 class): Unconfined scope runs the loads SYNCHRONOUSLY at VM construction, so the
    // panel composes with the settled outcome → only the deterministic waitForIdle() barrier, never a wall-clock
    // waitUntil(timeout) precondition that starves under parallel-suite CPU load.
    private fun vm(api: CommApi) = CommViewModel(api, IdleSource(), viewerId = "operator", scope = CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun channelsLoadFailure_showsErrorAndRetry_notEmpty() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(vm(FailingChannelsApi())) } } }
        waitForIdle()
        onNodeWithTag(CommTags.ERROR_CHANNELS).assertExists()
        onNodeWithTag(CommTags.ERROR_CHANNELS_RETRY).assertExists()
        onNodeWithTag(CommTags.EMPTY_CHANNELS).assertDoesNotExist() // error beats empty — the fix
    }

    @Test
    fun historyLoadFailure_showsErrorAndRetry_notEmpty() = runComposeUiTest {
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(vm(FailingHistoryApi())) } } }
        waitForIdle()
        onNodeWithTag(CommTags.ERROR_TIMELINE).assertExists()
        onNodeWithTag(CommTags.ERROR_TIMELINE_RETRY).assertExists()
        onNodeWithTag(CommTags.EMPTY_TIMELINE).assertDoesNotExist()
    }

    @Test
    fun genuinelyEmptyChannels_showsEmpty_notError() = runComposeUiTest {
        // Non-vacuous contrast: a SUCCESSFUL empty channel set still shows the plain empty state, never the error.
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(vm(EmptyApi())) } } }
        waitForIdle()
        onNodeWithTag(CommTags.EMPTY_CHANNELS).assertExists()
        onNodeWithTag(CommTags.ERROR_CHANNELS).assertDoesNotExist()
    }
}
