package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import com.tneff.cyppieagents.transport.NoiseJavaServerTerminator
import com.tneff.cyppieagents.transport.ServerRelayChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-M2 hardening H6 (S-Tester) — the **session-level** relay-drop honesty over a **REAL** Noise tunnel: drives the
 * real `RemoteHubSession` state machine with a real `NoiseJavaClientTransport` initiator ↔ a real
 * `NoiseJavaServerTerminator` responder over an in-memory relay (the only non-prod part). It replaces the fake-tunnel
 * unit cover (`Cyp443RemoteHubSessionTest`, virtual-time `FakeTunnel`) with an E2E real-crypto proof that:
 *  - the session reaches **CONNECTED** over a real handshake (Pinned trust + granted operator auth), and
 *  - a **relay drop** honestly transitions it to **RECONNECTING + inFlightUncertain** (H4 in-flight honesty), then
 *  - it **re-dials onto a fresh real tunnel** and **recovers to CONNECTED** with the uncertain flag cleared.
 *
 * The `RelayDialer` mints a fresh relay + launches a fresh responder on the SAME hub static per dial (so the client's
 * pin matches across re-dials); a non-zero backoff makes the transient RECONNECTING observable to the state collector.
 */
class CypM2SessionDropE2eTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @AfterTest fun tearDown() { scope.cancel() }

    @Test
    fun h6_realTunnelSession_relayDrop_reconnectingInFlightUncertain_thenRecovers() = runBlocking {
        val dh = RawKeys.generateX25519()
        // CYP-541 — the RECOVERY re-dial is GATED: the first dial connects immediately; the second (recovery) dial
        // blocks until the test has deterministically observed the honest in-flight-uncertain reconnecting posture.
        // This HOLDS that state stable, so the test does not race a fleeting StateFlow value — it is green under ANY
        // scheduling, including 16-concurrent-tunnel contention (the flake this fixes). Each dial mints a fresh relay +
        // a fresh real responder on the SAME hub static (the pin holds across re-dials).
        val dialCount = AtomicInteger()
        val releaseReDial = CompletableDeferred<Unit>()
        val dialer = RelayDialer { _ ->
            if (dialCount.getAndIncrement() >= 1) releaseReDial.await() // hold the recovery dial until the test releases it
            val (clientRelay, hubEnd) = InMemDuplex.pair()
            scope.launch { runCatching { NoiseJavaServerTerminator(dh.privateRaw).terminate(hubEnd) } }
            clientRelay
        }
        val trust = HubTrust { TrustResolution.Pinned(dh.publicRaw) }
        val auth = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted }
        val session = RemoteHubSession(
            hubId = "hub-1",
            transport = NoiseJavaClientTransport(),
            dialer = dialer,
            trust = trust,
            authenticator = auth,
            scope = scope,
            // Backoff no longer gates determinism (the re-dial hold does) — keep it tiny so the test is fast. The former
            // non-zero value was a timing crutch for observing the conflation-transient RECONNECTING sub-state.
            backoff = Backoff(initialMs = 1, maxMs = 1),
        )

        session.start()
        // Reach CONNECTED over the REAL handshake. The timeouts here are hang BACKSTOPS (a broken state machine fails
        // instead of hanging CI) — NOT race-timers: correctness comes from awaiting stable states, not from their length.
        val connected = withTimeoutOrNull(20_000) { session.state.first { it.conn == RemoteConnState.CONNECTED } }
        assertEquals(RemoteConnState.CONNECTED, connected?.conn, "the session reaches CONNECTED over a real Noise tunnel")
        assertTrue(!connected!!.inFlightUncertain, "at a clean CONNECTED the in-flight-uncertain flag is not set")

        // ★ a relay drop → an honest in-flight-uncertain reconnecting posture. `inFlightUncertain` is set at the
        // RECONNECTING transition (RemoteHubSession H4) and PERSISTS through RELAY_DIALING until recovery clears it —
        // the DURABLE honesty signal. Observed deterministically because the gated re-dial HOLDS the reconnecting state
        // (never a false CONNECTED, never a silent stall). We assert the durable signal, NOT the conflation-transient
        // conn==RECONNECTING sub-state: a MutableStateFlow legitimately drops that fleeting value under collector
        // starvation (the CYP-541 flake) — the signal is emitted correctly, so this is a TEST-observation fix, not a
        // product defect. (Repro before the fix: `Backoff(0,0)` shrinks the RECONNECTING window to nothing → the old
        // `seen`-collector assertion fails deterministically 3/3.)
        session.reportDropped()
        val uncertain = withTimeoutOrNull(20_000) {
            session.state.first { it.inFlightUncertain && it.conn != RemoteConnState.CONNECTED }
        }
        assertTrue(uncertain != null,
            "a relay drop honestly flags in-flight-uncertain in a reconnecting posture (H4), never a silent stall or a false CONNECTED")

        // Release the recovery dial → the session re-dials onto a FRESH real tunnel and recovers.
        releaseReDial.complete(Unit)
        val recovered = withTimeoutOrNull(20_000) {
            session.state.first { it.conn == RemoteConnState.CONNECTED && !it.inFlightUncertain }
        }
        assertTrue(recovered != null,
            "the session re-dials onto a FRESH real tunnel and recovers to CONNECTED with the uncertain flag cleared")
    }

    /** In-memory duplex: a client [RelayChannel] end and a hub [ServerRelayChannel] end sharing two queues. */
    private class InMemDuplex private constructor(
        private val outbound: Channel<ByteArray>,
        private val inbound: Channel<ByteArray>,
    ) : RelayChannel, ServerRelayChannel {
        override suspend fun send(frame: ByteArray) { outbound.send(frame.copyOf()) }
        override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()
        override suspend fun close() { outbound.close() }
        companion object {
            fun pair(): Pair<RelayChannel, ServerRelayChannel> {
                val c2h = Channel<ByteArray>(Channel.UNLIMITED)
                val h2c = Channel<ByteArray>(Channel.UNLIMITED)
                return InMemDuplex(outbound = c2h, inbound = h2c) to InMemDuplex(outbound = h2c, inbound = c2h)
            }
        }
    }
}
