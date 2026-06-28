package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-73 reviewer gate: **reconnect is idempotent**. The [CommViewModel] auto-reconnects (CYP-73), and
 * on every (re)connect the server replays its snapshot — here a source that re-emits the SAME
 * `message.id` on each subscription. After several reconnect cycles the timeline must still show the
 * message **exactly once** (`CommReducer.merge` dedups by id) — no duplicates, no visible loss.
 */
@OptIn(ExperimentalTestApi::class)
class CommReconnectIdempotencyTest {

    private val msg = Message("m1", "po-frontend", "frontend", "hallo PO", 1L)

    private class FakeApi : CommApi {
        override suspend fun channels() =
            listOf(Channel("po-frontend", "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
        )
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("real", channelId, "operator", body, 2L)
    }

    /** Replays `Connected → MessageReceived(msg) → Disconnected` each time it is collected; counts subs. */
    private class ReplayingSource(private val msg: Message) : CommLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<CommLiveEvent> = flow {
            subscriptions.value += 1
            emit(CommLiveEvent.Connected)
            emit(CommLiveEvent.MessageReceived(msg))
            emit(CommLiveEvent.Disconnected)
        }
    }

    @Test
    fun reconnect_replayingSameMessageId_keepsItExactlyOnce() = runComposeUiTest {
        val source = ReplayingSource(msg)
        setContent {
            MaterialTheme {
                val vm = androidx.compose.runtime.remember {
                    // Fast backoff so several reconnect cycles run within the test.
                    CommViewModel(FakeApi(), source, viewerId = "operator", backoff = Backoff(initialMs = 1, maxMs = 1))
                }
                CommPanel(vm)
            }
        }

        // Wait until the stream has reconnected at least twice (≥3 subscriptions) AND the row is shown.
        waitUntil(timeoutMillis = 5_000L) {
            source.subscriptions.value >= 3 &&
                onAllNodesWithTag(CommTags.message("m1")).fetchSemanticsNodes().isNotEmpty()
        }

        // Despite the message being replayed on every reconnect, the timeline shows it exactly once.
        assertEquals(
            1,
            onAllNodesWithTag(CommTags.message("m1")).fetchSemanticsNodes().size,
            "reconnect replays must be deduped by message.id (no duplicate timeline rows)",
        )
    }
}
