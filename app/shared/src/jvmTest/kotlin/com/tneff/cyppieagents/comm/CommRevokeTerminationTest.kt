package com.tneff.cyppieagents.comm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * CYP-291 — the Comm `/ws/comm` live stream must TERMINATE on a 1008 revoke (AccessRevoked), not reconnect. Comm
 * shares `/ws/comm` with ACL and has used `.reconnecting()` since CYP-73, so a revoked token that the server
 * closes with 1008 previously masqueraded as a transient `Disconnected` → the client re-opened the socket with
 * the revoked token every backoff period forever (the CYP-289 loop class, latent here). The fix: `CommWsClient`
 * maps 1008 (via the shared `readCloseCode`/`isAccessRevoked` helper) to `AccessRevoked`, and the VM cancels the
 * collector on it. A TRANSIENT drop still reconnects.
 *
 * Mutation proof: drop the `liveJob?.cancel()` on AccessRevoked → [terminalRevoke_terminates_doesNotReconnect]
 * REDs (subs climbs as it re-subscribes). The transient test keeps the fix from over-terminating.
 */
@OptIn(ExperimentalTestApi::class)
class CommRevokeTerminationTest {

    private class EmptyApi : CommApi {
        override suspend fun channels(): List<Channel> = emptyList()
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("x", channelId, "operator", body, 1L)
    }

    /** Emits Connected → AccessRevoked → completes, each subscription. The fix must cancel → subs stays 1. */
    private class RevokingCommSource : CommLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<CommLiveEvent> = flow {
            subscriptions.value += 1
            emit(CommLiveEvent.Connected)
            emit(CommLiveEvent.AccessRevoked)
        }
    }

    /** Transient drop on the 1st subscription; stays LIVE on re-subscribe. Must reconnect (subs ≥ 2). */
    private class DropThenRecoverCommSource : CommLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<CommLiveEvent> = flow {
            subscriptions.value += 1
            val n = subscriptions.value
            emit(CommLiveEvent.Connected)
            if (n == 1) emit(CommLiveEvent.Disconnected) else awaitCancellation()
        }
    }

    private fun vm(source: CommLiveSource) = CommViewModel(EmptyApi(), source, viewerId = "operator", backoff = Backoff(initialMs = 1, maxMs = 1))

    @Test
    fun terminalRevoke_terminates_doesNotReconnect() = runComposeUiTest {
        val source = RevokingCommSource()
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(remember { vm(source) }) } } }
        var reconnected = false
        try { waitUntil(timeoutMillis = 1500L) { source.subscriptions.value >= 2 }; reconnected = true } catch (_: Throwable) {}
        assertFalse(reconnected, "a 1008 revoke must terminate the Comm collector, not reconnect")
        assertEquals(1, source.subscriptions.value)
    }

    @Test
    fun transientDrop_stillReconnects() = runComposeUiTest {
        val source = DropThenRecoverCommSource()
        setContent { MaterialTheme { Box(Modifier.width(700.dp).height(700.dp)) { CommPanel(remember { vm(source) }) } } }
        waitUntil(timeoutMillis = 5_000L) { source.subscriptions.value >= 2 } // transient → reconnects (not terminated)
    }
}
