package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.relay.RelayWsConnector
import com.tneff.cyppieagents.net.hub.relay.RendezvousResolution
import com.tneff.cyppieagents.net.hub.relay.RendezvousResolver
import com.tneff.cyppieagents.net.hub.relay.RendezvousUnavailable
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-537 (M2 Option A, WS2) — [NoisePoolTunnelDialer] teeth against Backend's live WS1 responder seams
 * ([RendezvousResolver] + [RelayWsConnector], C4). [rendezvousSet] resolves once and returns the CP epoch-set
 * **minus the base id** (`drop(1)` — the session's control tunnel holds id_0); [dial] opens a SPECIFIC opaque id
 * then runs the pinned-handshake + PoP, fail-closed at every step, riding the pin the session adopted. Cancellation
 * always propagates.
 */
class NoisePoolTunnelDialerTest {

    private class FakeTunnel : NoiseTunnel {
        override val handshakeHash = ByteArray(32)
        var closed = false
        override suspend fun send(plaintext: ByteArray) = Unit
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { closed = true }
    }

    private class FakeRelay : RelayChannel {
        var closed = false
        override suspend fun send(frame: ByteArray) = Unit
        override suspend fun receive(): ByteArray? = null
        override suspend fun close() { closed = true }
    }

    private val pinnedStatic = ByteArray(32) { 7 }
    private val boundSet = listOf("id-0", "id-1", "id-2") // element 0 = the base id the session holds

    // ClientNoiseTransport is a *regular* interface (its connect() has a default `prologue`) ⇒ NOT SAM: use object :.
    private fun fakeTransport(tunnel: NoiseTunnel, throws: Boolean = false) = object : ClientNoiseTransport {
        override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel =
            if (throws) error("handshake broke") else tunnel
    }

    private fun dialer(
        trust: TrustResolution = TrustResolution.Pinned(pinnedStatic),
        connectThrows: Boolean = false,
        tunnel: FakeTunnel = FakeTunnel(),
        outcome: OperatorAuthOutcome = OperatorAuthOutcome.Granted,
        resolution: RendezvousResolution = RendezvousResolution.Bound("id-0", "ws://relay/x", boundSet),
        resolverThrows: Boolean = false,
        connector: RelayWsConnector = RelayWsConnector { _, _ -> FakeRelay() },
    ): NoisePoolTunnelDialer = NoisePoolTunnelDialer(
        hubId = "hub-1",
        transport = fakeTransport(tunnel, connectThrows),
        trust = HubTrust { trust },
        authenticator = OperatorAuthenticator { _, _ -> outcome },
        resolver = RendezvousResolver { if (resolverThrows) error("cp down") else resolution },
        connector = connector,
    )

    @Test
    fun rendezvousSet_dropsBaseId_returnsTheRest() = runTest {
        // The pool dials id_1..id_{cap-1}; id_0 is the session's control tunnel (no 1↔1 collision).
        assertEquals(listOf("id-1", "id-2"), dialer().rendezvousSet())
    }

    @Test
    fun rendezvousSet_resolveFailed_returnsNull() = runTest {
        val d = dialer(resolution = RendezvousResolution.Failed(RendezvousUnavailable.NOT_REGISTERED))
        assertNull(d.rendezvousSet(), "a typed Failed resolve ⇒ null fail-closed (pool INERT)")
    }

    @Test
    fun rendezvousSet_cpUnreachable_returnsNull() = runTest {
        assertNull(dialer(resolverThrows = true).rendezvousSet(), "a CP transport error ⇒ null fail-closed")
    }

    @Test
    fun rendezvousSet_emptySet_returnsEmpty_legacyInert() = runTest {
        // Legacy single-tunnel CP (rendezvousIds empty) ⇒ drop(1) of [] = [] ⇒ the pool has no ids (INERT).
        val d = dialer(resolution = RendezvousResolution.Bound("id-0", "ws://relay/x", emptyList()))
        assertEquals(emptyList(), d.rendezvousSet())
    }

    @Test
    fun dial_pinnedAndGranted_returnsTunnel_opensTheSpecificId() = runTest {
        val opened = mutableListOf<Pair<String, String>>()
        val d = dialer(connector = RelayWsConnector { url, id -> opened.add(url to id); FakeRelay() })
        val t = assertNotNull(d.dial("id-1"), "pinned + Granted ⇒ a live authenticated tunnel")
        assertTrue(t is FakeTunnel && !t.closed)
        assertEquals(listOf("ws://relay/x" to "id-1"), opened, "the relay opened THIS opaque id at the resolved url (C4)")
    }

    @Test
    fun dial_authRejected_returnsNull_closesTunnel() = runTest {
        val tunnel = FakeTunnel()
        val d = dialer(tunnel = tunnel, outcome = OperatorAuthOutcome.Rejected)
        assertNull(d.dial("id-1"), "a rejected PoP ⇒ null (fail-closed), never an unauthenticated tunnel")
        assertTrue(tunnel.closed, "the tunnel is torn down on reject")
    }

    @Test
    fun dial_deviceNotEnrolled_returnsNull_closesTunnel() = runTest {
        val tunnel = FakeTunnel()
        val d = dialer(tunnel = tunnel, outcome = OperatorAuthOutcome.DeviceNotEnrolled)
        assertNull(d.dial("id-1"), "a pool tunnel just fails-closed on any non-Granted outcome")
        assertTrue(tunnel.closed)
    }

    @Test
    fun dial_trustChanged_returnsNull_closesRelay_neverHandshakes() = runTest {
        val relay = FakeRelay()
        var connected = false
        val d = NoisePoolTunnelDialer(
            hubId = "hub-1",
            transport = object : ClientNoiseTransport {
                override suspend fun connect(pinnedHubStatic: ByteArray, relay: RelayChannel, prologue: ByteArray): NoiseTunnel {
                    connected = true; return FakeTunnel()
                }
            },
            trust = HubTrust { TrustResolution.Changed(expectedFingerprint = "AB:CD") },
            authenticator = OperatorAuthenticator { _, _ -> OperatorAuthOutcome.Granted },
            resolver = RendezvousResolver { RendezvousResolution.Bound("id-0", "ws://relay/x", boundSet) },
            connector = RelayWsConnector { _, _ -> relay },
        )
        assertNull(d.dial("id-1"), "a changed hub key is a hard block — NEVER a silently re-pinned pool tunnel (CI-5)")
        assertTrue(relay.closed, "the relay is closed on the trust block")
        assertTrue(!connected, "no handshake is attempted against a changed key")
    }

    @Test
    fun dial_relayOpenFails_returnsNull() = runTest {
        val d = dialer(connector = RelayWsConnector { _, _ -> error("relay down") })
        assertNull(d.dial("id-1"), "a relay-open failure ⇒ null (fail-closed)")
    }

    @Test
    fun dial_handshakeFails_returnsNull_closesRelay() = runTest {
        val relay = FakeRelay()
        val d = dialer(connectThrows = true, connector = RelayWsConnector { _, _ -> relay })
        assertNull(d.dial("id-1"), "a handshake failure ⇒ null")
        assertTrue(relay.closed, "the relay is closed on a handshake failure")
    }

    @Test
    fun dial_resolveFailed_returnsNull() = runTest {
        val d = dialer(resolution = RendezvousResolution.Failed(RendezvousUnavailable.RELAY_UNAVAILABLE))
        assertNull(d.dial("id-1"), "no resolved binding ⇒ dial fails closed (never opens a relay)")
    }

    @Test
    fun dial_cancellation_propagates_notSwallowed() = runTest {
        val d = dialer(connector = RelayWsConnector { _, _ -> throw CancellationException("switch") })
        assertFailsWith<CancellationException> { d.dial("id-1") }
    }
}
