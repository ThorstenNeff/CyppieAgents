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
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test

/**
 * CYP-289 — the ACL live stream must AUTO-RECONNECT on a socket drop (was honest-but-inert: an honest
 * DISCONNECTED banner but no reconnect and no retry → stale forever until the window is recreated). Now the
 * collector wraps `.reconnecting(backoff)` like Comm/AgentView, so a drop re-subscribes and — because the
 * source replays its `Connected` marker on (re)connect — the banner recovers to LIVE.
 *
 * Mutation proof: drop the `.reconnecting(backoff)` wrap → after the first drop the collector ends, `subs`
 * stays 1, the DISCONNECTED banner is permanent → this test times out (RED).
 */
@OptIn(ExperimentalTestApi::class)
class AclReconnectTest {

    /** 1st subscription drops (Connected→Disconnected→complete); every re-subscribe stays LIVE. Counts subs. */
    private class DropThenRecoverSource : AclLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<AclLiveEvent> = flow {
            subscriptions.value += 1
            val n = subscriptions.value
            emit(AclLiveEvent.Connected)
            if (n == 1) emit(AclLiveEvent.Disconnected) else awaitCancellation()
        }
    }

    @Test
    fun socketDrop_autoReconnects_andBannerRecovers() = runComposeUiTest {
        val source = DropThenRecoverSource()
        setContent {
            MaterialTheme {
                Box(Modifier.width(900.dp)) {
                    AclPanel(remember { AclViewModel(StubAclHub(), source, backoff = Backoff(initialMs = 1, maxMs = 1)) })
                }
            }
        }
        // Re-subscribed after the drop (subs ≥ 2) AND the DISCONNECTED banner cleared (connection back to LIVE).
        waitUntil(timeoutMillis = 5_000L) {
            source.subscriptions.value >= 2 && onAllNodesWithTag(AclMatrixTags.CONNECTION).fetchSemanticsNodes().isEmpty()
        }
        onNodeWithTag(AclMatrixTags.CONNECTION).assertDoesNotExist()
    }
}
