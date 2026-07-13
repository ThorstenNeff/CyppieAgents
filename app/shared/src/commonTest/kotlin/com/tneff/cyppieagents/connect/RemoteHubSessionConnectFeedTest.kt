package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-486 live-wiring — the live [RemoteHubSessionConnectFeed] teeth: it starts the session and emits its state
 * through to CONNECTED, and cancelling the collect (the Q5 exactly-one-hub switch / teardown) closes the
 * session (tunnel). Driven with the CYP-443 fake stack on an [UnconfinedTestDispatcher] (collectors settle
 * eagerly, the CYP-419 lesson).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHubSessionConnectFeedTest {

    private val hub = HubDescriptor("hub-1", "host-1", online = true, defaultPort = 8787, lastSeen = 1L)

    private class FakeTunnel(private val onClose: () -> Unit) : NoiseTunnel {
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { onClose() }
    }

    private class NoopRelay : RelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    private fun okSession(hub: HubDescriptor, scope: CoroutineScope, onTunnelClose: () -> Unit = {}): RemoteHubSession {
        // ClientNoiseTransport is a plain interface (its connect() has a default `prologue`, so it can't be a
        // `fun interface`) → an explicit object, not a SAM lambda.
        val transport = object : ClientNoiseTransport {
            override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
                FakeTunnel(onTunnelClose)
        }
        return RemoteHubSession(
            hubId = hub.hubId,
            transport = transport,
            dialer = RelayDialer { NoopRelay() },
            trust = HubTrust { TrustResolution.Pinned(ByteArray(32)) },
            authenticator = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted },
            scope = scope,
        )
    }

    @Test
    fun connect_startsSession_emitsStateThroughToConnected() = runTest {
        val feed = RemoteHubSessionConnectFeed { h, s -> okSession(h, s) }
        val seen = mutableListOf<RemoteConnState>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        scope.launch { feed.connect(hub).collect { seen.add(it.conn) } }
        advanceUntilIdle()
        assertTrue(RemoteConnState.CONNECTED in seen, "the live feed emits the session state through to CONNECTED")
        scope.cancel()
    }

    @Test
    fun cancellingCollect_closesSession_q5Teardown() = runTest {
        var tunnelClosed = false
        var built = 0
        val feed = RemoteHubSessionConnectFeed { h, s -> built++; okSession(h, s) { tunnelClosed = true } }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val job = scope.launch { feed.connect(hub).collect { } }
        advanceUntilIdle()
        assertEquals(1, built, "one session per connect")
        assertFalse(tunnelClosed, "tunnel stays open while connected")
        job.cancel()
        advanceUntilIdle()
        assertTrue(tunnelClosed, "cancelling the collect closes the session (Q5 teardown)")
        scope.cancel()
    }
}
