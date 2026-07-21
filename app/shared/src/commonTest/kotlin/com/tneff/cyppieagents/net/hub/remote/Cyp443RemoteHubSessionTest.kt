package com.tneff.cyppieagents.net.hub.remote

import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.trust.TrustConfirmationRejectedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-443 Slice 1 — the [RemoteHubSession] state machine honesty teeth. Fakes the crypto-auth seams (Slices 2/3)
 * so the sequence, reconnect, Q5 teardown, Q3 latency, and H4 in-flight honesty are pinned now. The VM runs on an
 * [UnconfinedTestDispatcher] so its loop settles eagerly at each assertion (the CYP-419 lesson).
 */
class Cyp443RemoteHubSessionTest {

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

    private class FakeTransport(private val fail: Int = 0) : ClientNoiseTransport {
        var calls = 0
        val tunnels = mutableListOf<FakeTunnel>()
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel {
            calls++
            if (calls <= fail) throw com.tneff.cyppieagents.net.hub.noise.NoiseHandshakeException("handshake boom")
            return FakeTunnel().also { tunnels += it }
        }
    }

    private val pinned = HubTrust { TrustResolution.Pinned(ByteArray(32)) }
    private val grant = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted }
    // A non-zero backoff so a reconnect delay actually suspends in virtual time → the transient RECONNECTING /
    // inFlightUncertain state is observable before advanceUntilIdle() drives the reconnect (delay(0) returns
    // immediately, which would make the reconnect synchronous and the transient state unobservable).
    private val slowBackoff = Backoff(initialMs = 1_000, maxMs = 1_000)

    private fun session(
        scope: CoroutineScope,
        transport: ClientNoiseTransport = FakeTransport(),
        dialer: RelayDialer = RelayDialer { NoopRelay() },
        trust: HubTrust = pinned,
        auth: OperatorAuthenticator = grant,
    ) = RemoteHubSession("hub-1", transport, dialer, trust, auth, scope, slowBackoff)

    /**
     * CYP-783 — the DETERMINISTIC regression guard for the arm-then-announce ordering (the lost-drop wedge the M2
     * full-suite acceptance surfaced). A drop reported EXACTLY at the announce/arm boundary — a consumer that
     * observes CONNECTED and immediately reports a tunnel death — must NOT be lost. The [onBeforeAnnounceConnected]
     * seam fires `reportDropped()` at that fixed boundary point: with the fix (`drop` armed BEFORE the announce)
     * the drop is CAPTURED → the session honestly enters a reconnecting/in-flight-uncertain posture; regress the
     * order (arm AFTER announce) and the same hook sees an unarmed latch → the drop is LOST → the session wedges
     * CONNECTED forever → `inFlightUncertain` never sets → this teeth reds. Non-vacuous by construction, and
     * deterministic (no widened-window `delay`, no flake) — the seam makes the multi-thread window observable.
     */
    @Test
    fun dropAtTheAnnounceArmBoundary_isNotLost_armThenAnnounce() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var dialCount = 0
        val releaseReDial = CompletableDeferred<Unit>() // hold the recovery dial so the reconnecting posture is observable
        val dialer = RelayDialer { if (dialCount++ >= 1) releaseReDial.await(); NoopRelay() }
        lateinit var s: RemoteHubSession
        var fired = false
        s = RemoteHubSession(
            "hub-1", FakeTransport(), dialer, pinned, grant, scope, slowBackoff,
            onBeforeAnnounceConnected = { if (!fired) { fired = true; s.reportDropped() } }, // the drop lands in the window, ONCE
        )
        s.start(); advanceUntilIdle()
        assertTrue(s.state.value.inFlightUncertain, "a drop reported at the announce/arm boundary must be CAPTURED (arm-then-announce) — regressing the order loses it and this reds")
        assertTrue(s.state.value.conn != RemoteConnState.CONNECTED, "the captured drop drove a reconnecting posture, never a wedged CONNECTED")
        releaseReDial.complete(Unit)
        scope.cancel()
    }

    @Test
    fun happyPath_reachesConnected_withTunnel() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val s = session(scope)
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn)
        assertNull(s.state.value.failure)
        assertTrue(s.tunnel != null, "the live tunnel is exposed for the CR3 engine")
        scope.cancel()
    }

    @Test
    fun trustChanged_isTerminal_hardBlock_noRetry() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val s = session(scope, transport = transport, trust = { TrustResolution.Changed("ab:cd") })
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.LOST, s.state.value.conn)
        assertEquals(RemoteFailure.TrustChanged("ab:cd"), s.state.value.failure)
        assertEquals(0, transport.calls, "a changed key must NEVER reach the handshake (CI-5)")
        scope.cancel()
    }

    @Test
    fun oobReject_isTerminal_failClosed_notSilentUnwind() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        // CYP-478/696: the operator OOB-rejects the first-use fingerprint. TofuHubTrust surfaces that as a
        // TrustConfirmationRejectedException (a CancellationException subclass) out of resolve(). The session must
        // convert it to an explicit terminal fail-closed LOST — NOT let it unwind the loop as a raw cancellation
        // that leaves the state stuck mid-connect (conn=RELAY_DIALING, failure=null).
        val s = session(scope, transport = transport, trust = { throw TrustConfirmationRejectedException("hub-1") })
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.LOST, s.state.value.conn, "an OOB reject lands a clean terminal LOST, never stale limbo")
        assertEquals(RemoteFailure.TrustRejected, s.state.value.failure)
        assertEquals(0, transport.calls, "a rejected first-use key must NEVER reach the handshake (nothing pinned)")
        assertNull(s.tunnel, "a rejected first use leaves no live tunnel")
        scope.cancel()
    }

    @Test
    fun authRejected_isTerminal_failClosed() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val s = session(scope, auth = { _, _ -> OperatorAuthOutcome.Rejected })
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.LOST, s.state.value.conn)
        assertEquals(RemoteFailure.AuthRejected, s.state.value.failure)
        assertNull(s.tunnel, "a rejected auth leaves no live tunnel")
        scope.cancel()
    }

    @Test
    fun drop_reconnects_andMarksInFlightUncertain() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val s = session(scope, transport = transport)
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn)
        val firstTunnel = transport.tunnels.first()

        s.reportDropped()
        assertEquals(RemoteConnState.RECONNECTING, s.state.value.conn)
        assertTrue(s.state.value.inFlightUncertain, "H4: in-flight work is uncertain on a drop, never assumed done")
        assertTrue(firstTunnel.closed, "the dropped tunnel is closed")

        advanceUntilIdle()
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn, "reconnects")
        assertFalse(s.state.value.inFlightUncertain, "uncertainty clears once reconnected")
        assertEquals(2, transport.calls)
        scope.cancel()
    }

    @Test
    fun close_tearsDown_toLost_exactlyOneHub() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val s = session(scope, transport = transport)
        s.start(); advanceUntilIdle()
        val tunnel = transport.tunnels.first()

        s.close()
        assertEquals(RemoteConnState.LOST, s.state.value.conn)
        assertNull(s.tunnel)
        assertTrue(tunnel.closed, "Q5 teardown closes the tunnel")
        scope.cancel()
    }

    @Test
    fun latency_isAdvisory_neverChangesConn() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val s = session(scope)
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn)

        repeat(5) { s.recordLatency(2000) } // sustained high latency
        assertTrue(s.state.value.latency!!.degraded, "Q3: degraded flag rises on real degradation")
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn, "H7: high latency ≠ disconnected")
        scope.cancel()
    }

    @Test
    fun relayUnreachable_thenRecovers() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var dialCalls = 0
        val dialer = RelayDialer {
            dialCalls++
            if (dialCalls == 1) throw RuntimeException("no relay") else NoopRelay()
        }
        val s = session(scope, dialer = dialer)
        s.start(); advanceUntilIdle()
        assertEquals(RemoteConnState.CONNECTED, s.state.value.conn, "a transient relay failure recovers")
        scope.cancel()
    }
}
