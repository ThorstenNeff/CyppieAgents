package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-819 (D2 / B1) — a terminal **1008 (AccessRevoked)** on `/ws/comm` sets the CommViewModel's OWN
 * [CommUiState.accessRevoked] flag (it is NOT folded into `connection = DISCONNECTED`, the safe-but-silent class).
 * That flag is what makes the revoked ERROR banner supersede the amber offline banner and hard-locks the composer
 * (independent of writability). Deterministic (injected scope + virtual time), like [CommRevokeTerminationTest] —
 * NOT a render test.
 *
 * Non-vacuity (mutation): dropping `_state.update { it.copy(accessRevoked = true) }` in `collectLive()` → the flag
 * stays false → [revoke_setsAccessRevokedFlag] REDs. A TRANSIENT drop (no revoke) leaves it false (control).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp819CommRevokedStateTest {

    private class EmptyApi : CommApi {
        override suspend fun channels(): List<Channel> = emptyList()
        override suspend fun agents(): List<Agent> = emptyList()
        override suspend fun messages(channelId: String, since: Long?): List<Message> = emptyList()
        override suspend fun send(channelId: String, body: String, meta: MessageMeta?): Message =
            Message("x", channelId, "operator", body, 1L)
    }

    /** Connected → AccessRevoked (a 1008), then completes. */
    private class RevokingCommSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow {
            emit(CommLiveEvent.Connected)
            emit(CommLiveEvent.AccessRevoked)
        }
    }

    /** Connected → Disconnected (a transient drop), then completes. */
    private class TransientDropCommSource : CommLiveSource {
        override fun events(): Flow<CommLiveEvent> = flow {
            emit(CommLiveEvent.Connected)
            emit(CommLiveEvent.Disconnected)
        }
    }

    @Test
    fun revoke_setsAccessRevokedFlag() = runTest {
        val vm = CommViewModel(EmptyApi(), RevokingCommSource(), viewerId = "operator", backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertTrue(vm.state.value.accessRevoked, "a 1008 AccessRevoked must set the distinct accessRevoked flag")
    }

    @Test
    fun transientDrop_leavesAccessRevokedFalse() = runTest {
        val vm = CommViewModel(EmptyApi(), TransientDropCommSource(), viewerId = "operator", backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertFalse(vm.state.value.accessRevoked, "a transient offline drop is NOT a revoke — the flag stays false")
    }
}
