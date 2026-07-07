package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

/**
 * CYP-279 (CYP-270/276 class) — the comm channel list must NOT flash "no channels" during the async load
 * window (cold open / project switch); only a SETTLED-empty channel set shows it. [GatedCommApi.channels]
 * suspends so the VM stays `loadingChannels = true` (its initial value).
 *
 * Mutation proof: drop the `!loadingChannels &&` guard at CommPanel.ChannelListPane → `comm_channels_empty`
 * renders WHILE loading → the first assertion of [channelList_neverFlashesEmptyDuringLoad_thenShowsWhenSettledEmpty] REDs.
 */
@OptIn(ExperimentalTestApi::class)
class CommChannelListEmptyFlashTest {

    private class GatedCommApi(val gate: CompletableDeferred<Unit>, val data: Boolean) : CommApi {
        override suspend fun channels(): List<Channel> {
            gate.await()
            return if (data) listOf(Channel("po-fe", "po-fe", ChannelKind.DIRECT, listOf("po", "fe"))) else emptyList()
        }
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("x", channelId, "operator", body, 1L)
    }

    @Test
    fun channelList_neverFlashesEmptyDuringLoad_thenShowsWhenSettledEmpty() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp).height(700.dp)) {
                    CommPanel(remember { CommViewModel(GatedCommApi(gate, data = false), StubCommLiveSource(), viewerId = "operator") })
                }
            }
        }
        waitForIdle()
        onNodeWithTag(CommTags.EMPTY_CHANNELS).assertDoesNotExist() // loading window → no "Keine Kanäle" flash
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.EMPTY_CHANNELS).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.EMPTY_CHANNELS).assertExists() // settled-empty → shows
    }

    @Test
    fun channelList_neverShowsEmpty_whenLoadYieldsChannels() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp).height(700.dp)) {
                    CommPanel(remember { CommViewModel(GatedCommApi(gate, data = true), StubCommLiveSource(), viewerId = "operator") })
                }
            }
        }
        waitForIdle()
        onNodeWithTag(CommTags.EMPTY_CHANNELS).assertDoesNotExist() // during load
        gate.complete(Unit)
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithTag(CommTags.CHANNEL_LIST).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(CommTags.EMPTY_CHANNELS).assertDoesNotExist() // settled with data → never
    }
}
