package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * CYP-513 — the ViewModel LIVE factory-path (VM-surfacing). When a [RemoteConnectComponentsFactory] is injected,
 * `connectRemote` drives the per-connect session AND surfaces its ①²-shared OOB coordinator: an `Awaiting` ⇒ the
 * mandatory confirm screen at TRUST_CHECK with the coordinator's presented bytes (display == pinned, end-to-end).
 * The `null`-factory path stays the CYP-510/471 feed behaviour (proven by those tests — INERT/byte-identical).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp513VmFactoryPathTest {

    private val bytes = ByteArray(32) { (it + 1).toByte() }
    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)

    private class FakeTunnel : NoiseTunnel {
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    private class NoopRelay : RelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    private val handshakingTransport = object : ClientNoiseTransport {
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel = FakeTunnel()
    }

    private class RecordingCoordinator(state: OobConfirmState, private val presented: Map<String, ByteArray>) : OobConfirmCoordinator {
        override val state: StateFlow<OobConfirmState> = MutableStateFlow(state)
        override fun approve() {}
        override fun reject() {}
        override suspend fun presentedStatic(hubId: String): ByteArray? = presented[hubId]?.copyOf()
    }

    @Test
    fun connectRemote_factoryPath_awaiting_surfacesLiveMount_atTrustCheck() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val factory = RemoteConnectComponentsFactory { h, sessionScope ->
            val session = RemoteHubSession(
                hubId = h.hubId,
                transport = handshakingTransport,
                dialer = RelayDialer { NoopRelay() },
                trust = HubTrust { TrustResolution.Pinned(ByteArray(32)) },
                authenticator = OperatorAuthenticator { _, _ -> true },
                scope = sessionScope,
                backoff = Backoff(initialMs = 1_000, maxMs = 1_000),
            )
            RemoteConnectComponents(session, RecordingCoordinator(OobConfirmState.Awaiting(h.hubId, "fp"), mapOf(h.hubId to bytes)))
        }
        val vm = HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = listOf(hub)),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteComponentsFactory = factory,
            scope = scope,
        )
        vm.start(); advanceUntilIdle(); vm.selectHub(hub); vm.connectRemote(); advanceUntilIdle()

        val s = assertIs<HubConnectUiState.RemoteConnecting>(vm.state.value)
        assertEquals(RemoteConnState.TRUST_CHECK, s.remote.conn, "Awaiting ⇒ the mandatory OOB screen at TRUST_CHECK")
        val mount = assertNotNull(s.oobConfirm, "the per-connect coordinator's OOB mount is surfaced (①② e2e)")
        assertFalse(mount.provisional)
        scope.cancel()
    }
}
