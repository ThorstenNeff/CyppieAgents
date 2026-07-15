package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import com.tneff.cyppieagents.net.hub.trust.OobConfirmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CYP-620 Step-2b — the VM mux-transport datapath **Pre-Flip belt**. The flag-ON path (span a `ClientMuxSession`
 * over `session.tunnel` on CONNECTED) is hard to unit-tooth end-to-end (real loopback transport), so this drives the
 * live factory-path VM to CONNECTED over a fake carrier and pins the two load-bearing decisions via an injected
 * `onMuxAttached` hook:
 *   1. **flag-gating** — flag OFF ⇒ NO mux attached (byte-identical to today = 0 merge-regression); flag ON ⇒ exactly one.
 *   2. **reference-identity guard** — a healthy `combine` re-emission (an OOB-state change while STILL CONNECTED on the
 *      SAME tunnel) does NOT rebuild the mux (no session-churn thrash). The rebuild-on-a-NEW-tunnel (reconnect) half is
 *      covered by Backend2's datapath-review + the drop→reportDropped→re-dial path (CYP-619).
 *
 * The transport build itself is jvm-loopback (null on web) and runs AFTER the hook fires, so the recorder assertion is
 * cross-target and never depends on real sockets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp620VmMuxTransportTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)

    /** A carrier whose `receive()` never returns — keeps the attached `ClientMuxSession.run()` alive (one stable attach;
     *  never an instant EOF that would fire reportDropped/reconnect churn mid-test). */
    private class BlockingTunnel : NoiseTunnel {
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = awaitCancellation()
        override suspend fun close() {}
    }

    private class NoopRelay : RelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    /** Hands out a fresh [BlockingTunnel] per connect and records them, so a test can assert the mux attached over the
     *  session's ACTUAL carrier (identity), not merely "a tunnel". */
    private class RecordingTransport : ClientNoiseTransport {
        val created = mutableListOf<NoiseTunnel>()
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
            BlockingTunnel().also { created.add(it) }
    }

    /** OOB coordinator with a flippable state, so a test can force a healthy `combine` re-emission (the identity-guard
     *  probe) without touching the session or its tunnel. */
    private class FlippableCoordinator : OobConfirmCoordinator {
        val mutable = MutableStateFlow<OobConfirmState>(OobConfirmState.Idle)
        override val state: StateFlow<OobConfirmState> = mutable
        override fun approve() {}
        override fun reject() {}
        override suspend fun presentedStatic(hubId: String): ByteArray? = null
    }

    private class Harness(
        val vm: HubConnectViewModel,
        val transport: RecordingTransport,
        val coord: FlippableCoordinator,
        val attached: List<NoiseTunnel>,
    )

    /** Builds a live factory-path VM that reaches CONNECTED (Pinned trust + Granted auth) over the recording transport. */
    private fun harness(scope: CoroutineScope, muxOn: Boolean): Harness {
        val transport = RecordingTransport()
        val coord = FlippableCoordinator()
        val attached = mutableListOf<NoiseTunnel>()
        val factory = RemoteConnectComponentsFactory { h, sessionScope ->
            val session = RemoteHubSession(
                hubId = h.hubId,
                transport = transport,
                dialer = RelayDialer { NoopRelay() },
                trust = HubTrust { TrustResolution.Pinned(ByteArray(32)) },
                authenticator = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted },
                scope = sessionScope,
                backoff = Backoff(initialMs = 1_000, maxMs = 1_000),
            )
            RemoteConnectComponents(session, coord)
        }
        val vm = HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = listOf(hub)),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteComponentsFactory = factory,
            muxEnabled = { muxOn },
            onMuxAttached = { attached.add(it) },
            scope = scope,
        )
        return Harness(vm, transport, coord, attached)
    }

    private fun TestScope.connect(h: Harness) {
        h.vm.start(); advanceUntilIdle()
        h.vm.selectHub(hub); h.vm.connectRemote(); advanceUntilIdle()
        // Sanity: the live session actually reached CONNECTED (else the mux-attach block never runs and the test is vacuous).
        assertTrue(h.transport.created.isNotEmpty(), "the session must have handshaked a carrier (reached CONNECTED)")
    }

    @Test
    fun flagOff_neverAttachesMux_byteIdenticalDatapath() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = harness(scope, muxOn = false)
        connect(h)
        assertTrue(h.attached.isEmpty(), "flag OFF ⇒ NO ClientMuxSession is spun (the today datapath, 0 regression)")
        scope.cancel()
    }

    @Test
    fun flagOn_attachesExactlyOneMuxOverTheLiveCarrier() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = harness(scope, muxOn = true)
        connect(h)
        assertEquals(1, h.attached.size, "flag ON + CONNECTED ⇒ exactly one mux attached")
        assertSame(h.transport.created.last(), h.attached.last(), "the mux is spun over the session's ACTUAL carrier tunnel")
        scope.cancel()
    }

    @Test
    fun flagOn_healthyReEmission_doesNotRebuild_identityGuard() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val h = harness(scope, muxOn = true)
        connect(h)
        assertEquals(1, h.attached.size, "one attach on the first CONNECTED")
        // A healthy `combine` re-emission WHILE still CONNECTED on the SAME tunnel (an OOB-state change, not a reconnect).
        h.coord.mutable.value = OobConfirmState.Rejected(hub.hubId)
        advanceUntilIdle()
        assertEquals(1, h.attached.size, "same carrier ⇒ the reference-identity guard skips the rebuild (no session-churn)")
        assertEquals(1, h.transport.created.size, "no reconnect happened — still the one handshaked carrier")
        scope.cancel()
    }
}
