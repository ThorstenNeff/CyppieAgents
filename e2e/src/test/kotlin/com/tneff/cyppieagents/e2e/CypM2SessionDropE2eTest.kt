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
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import com.tneff.cyppieagents.transport.NoiseJavaServerTerminator
import com.tneff.cyppieagents.transport.ServerRelayChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
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
        // Each dial: a fresh in-memory relay + a fresh real responder on the SAME hub static (the pin holds across re-dials).
        val dialer = RelayDialer { _ ->
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
            backoff = Backoff(initialMs = 250, maxMs = 250), // non-zero → RECONNECTING persists long enough to observe
        )

        // Record every state emission (the robust way to catch the transient RECONNECTING without conflation loss).
        val seen = CopyOnWriteArrayList<RemoteSessionState>()
        val collector = scope.launch { session.state.collect { seen += it } }

        session.start()
        // Reach CONNECTED over the REAL handshake.
        val connected = withTimeoutOrNull(8_000) { session.state.first { it.conn == RemoteConnState.CONNECTED } }
        assertEquals(RemoteConnState.CONNECTED, connected?.conn, "the session reaches CONNECTED over a real Noise tunnel")
        assertTrue(!connected!!.inFlightUncertain, "at a clean CONNECTED the in-flight-uncertain flag is not set")

        // ★ a relay drop → RECONNECTING + inFlightUncertain (honest in-flight signal), then recovery to CONNECTED.
        session.reportDropped()
        val recovered = withTimeoutOrNull(8_000) {
            session.state.first { it.conn == RemoteConnState.CONNECTED && !it.inFlightUncertain && seen.any { s -> s.conn == RemoteConnState.RECONNECTING } }
        }
        collector.cancel()

        assertTrue(seen.any { it.conn == RemoteConnState.RECONNECTING && it.inFlightUncertain },
            "a relay drop transitions the real session to RECONNECTING + inFlightUncertain (H4 honesty), never a silent stall")
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
