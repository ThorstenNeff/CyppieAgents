package com.tneff.cyppieagents.comm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-55 B1: the comm activity counter. Counts messages **from others** that arrive while the comm
 * window is **not** focused; **own** messages never count; gaining focus **resets** the count and
 * **suppresses** it until focus is lost again. Drives the real [CommViewModel] over a hand-fed live
 * source; `messages.size` is the deterministic processing watermark (the selected channel merges every
 * delivered message), so the unread assertions are checked only at settled points — no race.
 *
 * Mutation-proven: drop the reset → "unread == 0 after focus" never holds; drop the focus suppression
 * → the focused-arrival "c" leaks into the count; drop the `from != viewerId` filter → own message "e"
 * inflates the count.
 */
@OptIn(ExperimentalTestApi::class)
class CommViewModelUnreadTest {

    private val channelId = "po-frontend"

    private class FakeApi(private val channelId: String) : CommApi {
        override suspend fun channels() =
            listOf(Channel(channelId, "PO ↔ Frontend", ChannelKind.HUB, listOf("po", "frontend")))
        override suspend fun agents() = listOf(Agent("frontend", "Frontend", Role.WORKER, "frontend"))
        override suspend fun messages(channelId: String, since: Long?) = emptyList<Message>()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?) =
            Message("real", channelId, "operator", body, 99L)
    }

    /** A hot live source the test feeds directly — never completes, so the VM keeps one subscription. */
    private class FeedSource : CommLiveSource {
        val flow = MutableSharedFlow<CommLiveEvent>(extraBufferCapacity = 64)
        override fun events(): Flow<CommLiveEvent> = flow
    }

    private fun received(id: String, from: String, ts: Long) =
        CommLiveEvent.MessageReceived(Message(id, "po-frontend", from, "body-$id", ts))

    @Test
    fun unread_countsOthersUnfocused_ignoresOwn_resetsAndSuppressesOnFocus() = runComposeUiTest {
        val source = FeedSource()
        lateinit var vm: CommViewModel
        setContent {
            MaterialTheme {
                vm = remember {
                    CommViewModel(
                        FakeApi(channelId), source, viewerId = "operator",
                        backoff = Backoff(initialMs = 1, maxMs = 1),
                    )
                }
            }
        }

        // Collector subscribed AND the first channel auto-selected → live messages merge deterministically.
        waitUntil(timeoutMillis = 5_000L) {
            source.flow.subscriptionCount.value > 0 && vm.state.value.selectedChannelId == channelId
        }

        // Unfocused (default): two others count, the own ("e") is ignored. messages merges all three.
        source.flow.tryEmit(received("a", "frontend", 1L))
        source.flow.tryEmit(received("e", "operator", 2L))
        source.flow.tryEmit(received("b", "frontend", 3L))
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.messages.size == 3 && vm.state.value.unreadCount == 2 }
        assertEquals(2, vm.state.value.unreadCount)

        // Focus → reset to 0.
        vm.markCommFocused(true)
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.unreadCount == 0 }

        // While focused: "c" is processed (merged → size 4) but must NOT count.
        source.flow.tryEmit(received("c", "frontend", 4L))
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.messages.size == 4 }
        assertEquals(0, vm.state.value.unreadCount)

        // Unfocus again: only the next arrival ("d") counts — proves "c" was suppressed, not deferred.
        vm.markCommFocused(false)
        source.flow.tryEmit(received("d", "frontend", 5L))
        waitUntil(timeoutMillis = 5_000L) { vm.state.value.messages.size == 5 && vm.state.value.unreadCount == 1 }
        assertEquals(1, vm.state.value.unreadCount)
    }
}
