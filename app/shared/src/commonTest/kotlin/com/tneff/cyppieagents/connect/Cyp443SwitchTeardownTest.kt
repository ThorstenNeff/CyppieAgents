package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.operator.UvReason
import com.tneff.cyppieagents.net.hub.operator.vault.DecryptedKeyHold
import com.tneff.cyppieagents.net.hub.pool.PoolTunnelDialer
import com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-443 (Q5 „auf Hub wechseln", CI-6) — the **exactly-one-hub switch carries NOTHING across**, proven
 * *structurally* through the REAL live-components path.
 *
 * **Why this exists (the gap the CYP-443 verify found):** the only prior switch tooth
 * ([Cyp471RemoteConnectViewModelTest.q5Switch_exactlyOneHub_newReplacesOld]) is **outcome-only** — it drives the
 * switch over [StubRemoteConnectFeed], which never builds `activeComponents`, so
 * [HubConnectViewModel.closeActiveComponents] (`session.close` · `tunnelPool.close` · `keyHold.clear` ·
 * `enrollConfirm.abort` · `clearPreArm`) is **never exercised**. That is the two-barrier trap: the stub masks a
 * severed teardown, so CI-6 was *code* but *unproven*. These teeth inject a recording
 * [RemoteConnectComponentsFactory] (the same off-default seam App.kt wires) built from a REAL [RemoteHubSession]
 * over fake crypto seams + observable per-connect secrets, so a switch's teardown of **each** barrier is directly
 * asserted — one @Test per barrier, so a single-line mutation of that barrier reddens exactly its tooth.
 *
 * **Session note (defence-in-depth, honest):** the session has TWO independent closers on the switch path — the
 * connect job's structured-concurrency `finally` (HubConnectViewModel §"Q5 teardown on cancel / switch") AND
 * `closeActiveComponents`. So a single-line mutation of *either* session closer is masked by the other (verified);
 * [switch_severs_previousSession] asserts the CI-6 *outcome* (LOST + tunnel closed), load-bearing against both
 * severed. The other four barriers are single-closer (only in `closeActiveComponents`) → clean per-barrier red.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp443SwitchTeardownTest {

    private val hubA = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private val hubB = HubDescriptor("hub-b", "host-b", online = true, defaultPort = 8787, lastSeen = 1L)

    private class FakeTunnel : NoiseTunnel {
        var closed = false
        override val handshakeHash = ByteArray(32) { 0x11 }
        override suspend fun send(plaintext: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { closed = true }
    }

    private class NoopRelay : RelayChannel {
        override suspend fun send(frame: ByteArray) {}
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() {}
    }

    /** Captures the [FakeTunnel] the session's handshake produced, so a switch's `session.close()` is observable. */
    private class CapturingTransport : ClientNoiseTransport {
        val tunnels = mutableListOf<FakeTunnel>()
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
            FakeTunnel().also { tunnels += it }
    }

    /** A pool dialer whose dialed [FakeTunnel]s are observable — a switch's `tunnelPool.close()` closes them. */
    private class FakePoolDialer : PoolTunnelDialer {
        val dialed = mutableListOf<FakeTunnel>()
        override suspend fun rendezvousSet(): List<String> = listOf("r0", "r1", "r2")
        override suspend fun dial(rendezvousId: String): NoiseTunnel = FakeTunnel().also { dialed += it }
    }

    /** Idle OOB coordinator ⇒ no mount at TRUST_CHECK ⇒ the session drives straight through to CONNECTED. */
    private class IdleOob : OobConfirmCoordinator {
        override val state: StateFlow<OobConfirmState> = MutableStateFlow(OobConfirmState.Idle)
        override fun approve() {}
        override fun reject() {}
        override suspend fun presentedStatic(hubId: String): ByteArray? = null
    }

    private class RecordingEnroll : EnrollConfirmCoordinator {
        var aborted = false
        override val state: StateFlow<EnrollConfirmState> = MutableStateFlow(EnrollConfirmState.Idle)
        override suspend fun confirmSavedCodes(codes: List<String>): Boolean = true
        override fun confirmSaved() {}
        override fun abort() { aborted = true }
    }

    private class RecordingPassphrase : PassphrasePromptCoordinator {
        var clearPreArmCalled = false
        override val state: StateFlow<PassphrasePromptState> = MutableStateFlow(PassphrasePromptState.Idle)
        override suspend fun prompt(reason: UvReason): CharArray? = null
        override fun submit(passphrase: CharArray) {}
        override fun cancel() {}
        override fun preArm(passphrase: CharArray) {}
        override fun clearPreArm() { clearPreArmCalled = true }
    }

    private class Rec(
        val comps: RemoteConnectComponents,
        val sessionTransport: CapturingTransport,
        val poolDialer: FakePoolDialer,
    )

    private class Harness(val vm: HubConnectViewModel, val recs: List<Rec>, val scope: CoroutineScope)

    private fun recordingFactory(recs: MutableList<Rec>) = RemoteConnectComponentsFactory { h, sessionScope ->
        val transport = CapturingTransport()
        val poolDialer = FakePoolDialer()
        val session = RemoteHubSession(
            hubId = h.hubId,
            transport = transport,
            dialer = RelayDialer { NoopRelay() },
            trust = HubTrust { TrustResolution.Pinned(ByteArray(32)) },
            authenticator = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted },
            scope = sessionScope,
            backoff = Backoff(initialMs = 1_000, maxMs = 1_000),
        )
        val comps = RemoteConnectComponents(
            session = session,
            oobConfirm = IdleOob(),
            enrollConfirm = RecordingEnroll(),
            tunnelPool = PooledTunnelSource(dialer = poolDialer, nowMs = { 0L }),
            passphrasePrompt = RecordingPassphrase(),
            keyHold = DecryptedKeyHold(nowMs = { 0L }),
        )
        recs += Rec(comps, transport, poolDialer)
        comps
    }

    /**
     * Connect hub-A through the LIVE factory path (so `activeComponents` is set) and SEED the two per-connect
     * secrets a real CONNECTED session holds — a decrypted device key in the hold + one live pool tunnel — so a
     * subsequent switch's teardown of EACH is observable. Returns the [Harness]; the caller drives the switch.
     */
    private suspend fun TestScope.connectHubAAndSeed(): Harness {
        val recs = mutableListOf<Rec>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val vm = HubConnectViewModel(
            controlPlane = StubControlPlaneClient(hubs = listOf(hubA, hubB)),
            credentials = StubHubCredentialRepository(),
            connectFeed = StubLocalConnectFeed(),
            remoteComponentsFactory = recordingFactory(recs),
            scope = scope,
        )
        vm.start(); advanceUntilIdle()
        vm.selectHub(hubA); vm.connectRemote(); advanceUntilIdle()
        val a = recs.first().comps
        assertEquals(RemoteConnState.CONNECTED, a.session.state.value.conn, "precondition: hub-A is CONNECTED")
        // Seed the two live secrets the real factory holds at CONNECTED, so their teardown is observable:
        (a.keyHold as DecryptedKeyHold).put(byteArrayOf(7, 7, 7, 7), expiresAtMs = Long.MAX_VALUE)
        a.tunnelPool!!.acquire() // one live pooled tunnel (its delegate is poolDialer.dialed.last())
        assertNotNull(a.keyHold!!.get(), "precondition: decrypted key is held")
        assertTrue(recs.first().poolDialer.dialed.isNotEmpty(), "precondition: a pool tunnel is live")
        assertTrue(recs.first().poolDialer.dialed.none { it.closed }, "precondition: pool tunnels open")
        return Harness(vm, recs, scope)
    }

    // --- one @Test per teardown barrier (single-line mutation of that barrier reddens exactly this tooth) ---

    @Test
    fun switch_severs_previousSession() = runTest {
        val h = connectHubAAndSeed()
        h.vm.backToHubList(); advanceUntilIdle()
        val a = h.recs.first()
        assertEquals(RemoteConnState.LOST, a.comps.session.state.value.conn, "Q5/CI-6: the previous session is torn down")
        assertTrue(a.sessionTransport.tunnels.first().closed, "the previous session's live Noise tunnel is closed")
        h.scope.cancel()
    }

    @Test
    fun switch_closes_previousPoolTunnels() = runTest {
        val h = connectHubAAndSeed()
        h.vm.backToHubList(); advanceUntilIdle()
        assertTrue(
            h.recs.first().poolDialer.dialed.isNotEmpty() && h.recs.first().poolDialer.dialed.all { it.closed },
            "Q5/CI-6: a switch tears down the previous pool's live tunnels — the transport never closes them, only pool.close() does",
        )
        h.scope.cancel()
    }

    @Test
    fun switch_zeroizes_previousDecryptedKeyHold() = runTest {
        val h = connectHubAAndSeed()
        h.vm.backToHubList(); advanceUntilIdle()
        assertNull(
            h.recs.first().comps.keyHold!!.get(),
            "Q5/CI-6/H-1: a switch zeroizes the previous connect's decrypted device key (never left GC-reachable)",
        )
        h.scope.cancel()
    }

    @Test
    fun switch_aborts_previousEnrollConfirm() = runTest {
        val h = connectHubAAndSeed()
        h.vm.backToHubList(); advanceUntilIdle()
        assertTrue(
            (h.recs.first().comps.enrollConfirm as RecordingEnroll).aborted,
            "Q5/CI-6: a switch aborts a mid-flight enroll reveal (fail-closed, no SavedAck carried across)",
        )
        h.scope.cancel()
    }

    @Test
    fun switch_clearsPreArm_onPreviousPassphraseCoordinator() = runTest {
        val h = connectHubAAndSeed()
        h.vm.backToHubList(); advanceUntilIdle()
        assertTrue(
            (h.recs.first().comps.passphrasePrompt as RecordingPassphrase).clearPreArmCalled,
            "Q5/CI-6: a switch zeroizes any un-consumed enroll pre-arm passphrase",
        )
        h.scope.cancel()
    }

    @Test
    fun switch_buildsFreshDistinctComponents_forNewHub() = runTest {
        val h = connectHubAAndSeed()
        h.vm.backToHubList(); advanceUntilIdle()
        h.vm.selectHub(hubB); h.vm.connectRemote(); advanceUntilIdle()
        assertEquals(2, h.recs.size, "the new hub builds a fresh components set (not a reused one)")
        assertNotSame(h.recs[0].comps, h.recs[1].comps, "exactly-one-hub: a fresh components instance")
        assertNotSame(h.recs[0].comps.session, h.recs[1].comps.session, "and a fresh session — no reused tunnel/pin/UV")
        val s = assertIs<HubConnectUiState.RemoteConnecting>(h.vm.state.value)
        assertEquals(hubB.hubId, s.hub.hubId, "the switch lands on the new hub")
        assertEquals(RemoteConnState.CONNECTED, s.remote.conn)
        h.scope.cancel()
    }
}
