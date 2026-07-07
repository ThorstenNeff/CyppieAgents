package com.tneff.cyppieagents.eventlog

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
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

/**
 * CYP-289 — the Event Live-Tail must AUTO-RECONNECT on a socket drop (was honest-but-inert: an honest
 * DISCONNECTED banner but no reconnect → stale forever). Now the collector wraps `.reconnecting(backoff)` like
 * Comm/AgentView, so a drop re-subscribes and the banner recovers to LIVE on the replayed `Connected` marker.
 * AccessRevoked (1008) is still handled honestly (a real terminal reject, not a transient drop).
 *
 * Mutation proof: drop the `.reconnecting(backoff)` wrap → the collector ends after the first drop, `subs` stays
 * 1, the DISCONNECTED banner is permanent → this test times out (RED).
 */
@OptIn(ExperimentalTestApi::class)
class EventTailReconnectTest {

    /** 1st subscription drops (Connected→Disconnected→complete); every re-subscribe stays LIVE. Counts subs. */
    private class DropThenRecoverSource : EventLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow {
            subscriptions.value += 1
            val n = subscriptions.value
            emit(EventLiveEvent.Connected)
            if (n == 1) emit(EventLiveEvent.Disconnected) else awaitCancellation()
        }
    }

    @Test
    fun socketDrop_autoReconnects_andBannerRecovers() = runComposeUiTest {
        val source = DropThenRecoverSource()
        setContent {
            MaterialTheme {
                Box(Modifier.width(700.dp).height(700.dp)) {
                    EventTailPanel(remember { EventTailViewModel(source, backoff = Backoff(initialMs = 1, maxMs = 1)) })
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            source.subscriptions.value >= 2 && onAllNodesWithTag(EventTailTags.CONNECTION).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(EventTailTags.CONNECTION).assertDoesNotExist()
    }
}
