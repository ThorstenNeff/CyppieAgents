package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-291 guard — the Comm `/ws/comm` live stream must TERMINATE on a 1008 revoke (AccessRevoked), not reconnect;
 * a TRANSIENT drop still must. Twin of `acl.AclAccessRevokedTest` / `eventlog.EventTailAccessRevokedTest`.
 *
 * CYP-294 de-flake: driven on an INJECTED scope + VIRTUAL time (testScheduler) like the ACL/EventTail guards. The
 * previous runComposeUiTest + wall-clock waitUntil raced with `viewModelScope` + `Backoff(1ms)` under parallel-
 * suite load (latent flake, no regression — the fix is real). Deterministic now; non-vacuity preserved: dropping
 * `liveJob.cancel()` on AccessRevoked → [terminalRevoke_terminates_doesNotReconnect] REDs (subs climbs, ~1001/s).
 */
class CommRevokeTerminationTest {

    private class EmptyApi : CommApi {
        override suspend fun channels(): List<Channel> = emptyList()
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("x", channelId, "operator", body, 1L)
    }

    /** Connected → AccessRevoked → complete, each subscription. The fix cancels the collector → subs stays 1. */
    private class RevokingCommSource : CommLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<CommLiveEvent> = flow {
            subscriptions.value += 1
            emit(CommLiveEvent.Connected)
            emit(CommLiveEvent.AccessRevoked)
        }
    }

    /** Transient drop on the 1st subscription; stays LIVE on re-subscribe → must reconnect (subs ≥ 2). */
    private class DropThenRecoverCommSource : CommLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(): Flow<CommLiveEvent> = flow {
            subscriptions.value += 1
            val n = subscriptions.value
            emit(CommLiveEvent.Connected)
            if (n == 1) emit(CommLiveEvent.Disconnected) else awaitCancellation()
        }
    }

    @Test
    fun terminalRevoke_terminates_doesNotReconnect() = runTest {
        val source = RevokingCommSource()
        CommViewModel(EmptyApi(), source, viewerId = "operator", backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, source.subscriptions.value, "Comm AccessRevoked (1008) is terminal — subscriptions>1 means a reconnect LOOP on /ws/comm")
    }

    @Test
    fun transientDrop_stillReconnects() = runTest {
        val source = DropThenRecoverCommSource()
        CommViewModel(EmptyApi(), source, viewerId = "operator", backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue(source.subscriptions.value >= 2, "a transient drop (not AccessRevoked) must reconnect")
    }
}
