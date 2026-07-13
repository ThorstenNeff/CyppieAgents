package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-504 (Zahn C, non-vacuous) — the [buildRemoteHubSession] seam is the naht that makes the CYP-486
 * "fails closed at dial" property **load-bearing** rather than result-blind. Three seams gate the connect —
 * dial (runway #1), TOFU trust (runway #2), operator-auth (runway #4) — and this proves, per seam, that
 * CONNECTED is reachable **only when all three pass**, so each one independently blocks (defence-in-depth: even
 * if two are opened too early at activation, the third still holds).
 *
 *  - **P1** positive-control: all seams open ⇒ CONNECTED IS reachable (this is what makes the negatives
 *    non-vacuous — [RemoteHubAssemblyTest]'s bare `assertFalse(CONNECTED)` was vacuous because the real Noise
 *    transport can never handshake a dead relay, so CONNECTED was unreachable regardless).
 *  - **N1/N2/N3** per-seam-negatives: open two seams, gate the third ⇒ NOT CONNECTED. Each is mutation-sharp
 *    by construction — re-opening its gated seam (the mutation vs P1) makes CONNECTED reappear.
 *  - **F1** dial-isolation (Trust-Spy): the gated dial fails closed **before** [HubTrust.resolve] is reached,
 *    so the dial seam is pinned in isolation from the dial∧trust→RelayUnreachable enum collision.
 *
 * The session loop retries transient failures forever (dial/trust throw ⇒ TRANSIENT), so — like
 * [RemoteHubAssemblyTest] — the collector cancels on the first CONNECTED-or-failure to bound the virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp504BuildRemoteHubSessionTest {

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

    // ---- the three seams, PASSING form ----
    private val handshakingTransport = object : ClientNoiseTransport {
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel = FakeTunnel()
    }
    private val openDialer = RelayDialer { NoopRelay() }
    private val pinnedTrust = HubTrust { TrustResolution.Pinned(ByteArray(32)) }
    private val grantingAuth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted }

    // ---- the three seams, GATED form (mirrors the prod jvm assembly) ----
    private val gatedDialer = RelayDialer { error("client relay rendezvous not available (runway #1)") }
    private val gatedTrust = HubTrust { error("presented key empty (runway #2 gated)") }
    private val gatedAuth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Rejected } // cpJwt null ⇒ fail-closed
    private val notEnrolledAuth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.DeviceNotEnrolled } // CYP-525

    /**
     * Drive the builder's session and record its states, cancelling once it CONNECTs or fails. The session is
     * built with the collector coroutine's own scope (`this`) as its [CoroutineScope], so `cancel()` tears down
     * the retry loop too (a sibling scope would leave it looping every backoff tick → `advanceUntilIdle()` hangs).
     */
    private fun drive(
        outerScope: CoroutineScope,
        seen: MutableList<RemoteSessionState>,
        transport: ClientNoiseTransport = handshakingTransport,
        dialer: RelayDialer = openDialer,
        trust: HubTrust = pinnedTrust,
        auth: OperatorAuthenticator = grantingAuth,
    ) {
        outerScope.launch {
            val s = buildRemoteHubSession("hub-1", transport, dialer, trust, auth, this)
            s.start()
            s.state.collect {
                seen.add(it)
                if (it.conn == RemoteConnState.CONNECTED || it.failure != null) cancel()
            }
        }
    }

    @Test
    fun p1_allSeamsOpen_reachesConnected() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        drive(scope, seen)
        advanceUntilIdle()
        assertTrue(
            seen.any { it.conn == RemoteConnState.CONNECTED },
            "all three seams open ⇒ CONNECTED IS reachable (makes the per-seam negatives non-vacuous)",
        )
        scope.cancel()
    }

    @Test
    fun n1_onlyDialGated_neverConnects() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        drive(scope, seen, dialer = gatedDialer) // trust + transport + auth all pass
        advanceUntilIdle()
        assertFalse(seen.any { it.conn == RemoteConnState.CONNECTED }, "the dial seam alone blocks CONNECTED")
        assertTrue(seen.any { it.failure == RemoteFailure.RelayUnreachable }, "fails closed at dial")
        scope.cancel()
    }

    @Test
    fun n2_onlyTrustGated_neverConnects() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        drive(scope, seen, trust = gatedTrust) // dial + transport + auth all pass
        advanceUntilIdle()
        assertFalse(seen.any { it.conn == RemoteConnState.CONNECTED }, "the trust seam alone blocks CONNECTED")
        assertTrue(seen.any { it.failure != null }, "a gated presented-key fails closed (never a blind connect)")
        scope.cancel()
    }

    @Test
    fun n3_onlyAuthGated_neverConnects() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        drive(scope, seen, auth = gatedAuth) // dial + trust + transport all pass
        advanceUntilIdle()
        assertFalse(seen.any { it.conn == RemoteConnState.CONNECTED }, "the operator-auth seam alone blocks CONNECTED")
        assertTrue(seen.any { it.failure == RemoteFailure.AuthRejected }, "fails closed at operator-auth (a∧b∧c said no)")
        scope.cancel()
    }

    @Test
    fun cyp525_authDeviceNotEnrolled_failsDistinct_neverAuthRejected() = runTest {
        // GE4: not-enrolled is its own terminal truth (RemoteFailure.DeviceNotEnrolled → the enroll step), and it
        // must NEVER collapse into AuthRejected ("the hub denied you") — that conflation is the CYP-525 bug root.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        drive(scope, seen, auth = notEnrolledAuth) // dial + trust + transport all pass
        advanceUntilIdle()
        assertFalse(seen.any { it.conn == RemoteConnState.CONNECTED }, "not-enrolled never connects")
        assertTrue(seen.any { it.failure == RemoteFailure.DeviceNotEnrolled }, "routes to the distinct ENROLL truth")
        assertFalse(seen.any { it.failure == RemoteFailure.AuthRejected }, "NEVER collapses into AuthRejected (the bug)")
        scope.cancel()
    }

    @Test
    fun f1_dialFailsClosed_beforeTrustResolve() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<RemoteSessionState>()
        var trustResolveReached = false
        val spyTrust = HubTrust {
            trustResolveReached = true
            TrustResolution.Pinned(ByteArray(32))
        }
        drive(scope, seen, dialer = gatedDialer, trust = spyTrust)
        advanceUntilIdle()
        assertTrue(seen.any { it.failure == RemoteFailure.RelayUnreachable }, "dial fails closed")
        assertFalse(trustResolveReached, "dial fails closed BEFORE trust.resolve → the dial seam is isolated")
        scope.cancel()
    }
}
