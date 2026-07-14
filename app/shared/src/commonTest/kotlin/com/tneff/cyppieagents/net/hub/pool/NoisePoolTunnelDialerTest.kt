package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-537 (M2 Option A, WS2) — [NoisePoolTunnelDialer] establishment teeth: the pool tunnel's crypto path
 * (dial-the-opaque-id → resolve trust → Noise handshake → operator PoP) is **fail-closed at every step** and rides
 * the pin the session already adopted. A Granted PoP yields the tunnel; anything else (reject / trust-changed /
 * handshake fail / relay fail) yields `null` with the relay/tunnel torn down first — never a leaked or unauthenticated
 * tunnel. Cancellation always propagates (a switch/leave unwinds, never swallowed as a fail).
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
        relay: FakeRelay = FakeRelay(),
        set: List<String>? = listOf("id-0", "id-1"),
        relayDial: suspend (String) -> RelayChannel = { relay },
    ): NoisePoolTunnelDialer = NoisePoolTunnelDialer(
        hubId = "hub-1",
        transport = fakeTransport(tunnel, connectThrows),
        trust = HubTrust { trust },
        authenticator = OperatorAuthenticator { _, _ -> outcome },
        rendezvousSetResolver = { set },
        dialRendezvous = relayDial,
    )

    @Test
    fun dial_pinnedAndGranted_returnsTunnel_dialsTheId() = runTest {
        val dialedIds = mutableListOf<String>()
        val d = dialer(relayDial = { id -> dialedIds.add(id); FakeRelay() })
        val t = assertNotNull(d.dial("id-0"), "pinned + Granted ⇒ a live authenticated tunnel")
        assertTrue(t is FakeTunnel)
        assertFalse(t.closed)
        assertEquals(listOf("id-0"), dialedIds, "the specific opaque id was dialed (C4)")
    }

    @Test
    fun dial_authRejected_returnsNull_closesTunnel() = runTest {
        val tunnel = FakeTunnel()
        val d = dialer(tunnel = tunnel, outcome = OperatorAuthOutcome.Rejected)
        assertNull(d.dial("id-0"), "a rejected PoP ⇒ null (fail-closed), never an unauthenticated tunnel")
        assertTrue(tunnel.closed, "the tunnel is torn down on reject")
    }

    @Test
    fun dial_deviceNotEnrolled_returnsNull_closesTunnel() = runTest {
        val tunnel = FakeTunnel()
        val d = dialer(tunnel = tunnel, outcome = OperatorAuthOutcome.DeviceNotEnrolled)
        assertNull(d.dial("id-0"), "a pool tunnel just fails-closed on any non-Granted outcome")
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
            rendezvousSetResolver = { listOf("id-0") },
            dialRendezvous = { relay },
        )
        assertNull(d.dial("id-0"), "a changed hub key is a hard block — NEVER a silently re-pinned pool tunnel (CI-5)")
        assertTrue(relay.closed, "the relay is closed on the trust block")
        assertFalse(connected, "no handshake is attempted against a changed key")
    }

    @Test
    fun dial_relayUnreachable_returnsNull() = runTest {
        val d = dialer(relayDial = { error("relay down") })
        assertNull(d.dial("id-0"), "a relay-open failure ⇒ null (fail-closed)")
    }

    @Test
    fun dial_handshakeFails_returnsNull_closesRelay() = runTest {
        val relay = FakeRelay()
        val d = dialer(connectThrows = true, relayDial = { relay })
        assertNull(d.dial("id-0"), "a handshake failure ⇒ null")
        assertTrue(relay.closed, "the relay is closed on a handshake failure")
    }

    @Test
    fun rendezvousSet_delegatesToResolver() = runTest {
        assertEquals(listOf("id-0", "id-1"), dialer().rendezvousSet())
        assertNull(dialer(set = null).rendezvousSet(), "an unresolved CP set is null fail-closed")
    }

    @Test
    fun dial_cancellation_propagates_notSwallowed() = runTest {
        val d = dialer(relayDial = { throw CancellationException("switch") })
        assertFailsWith<CancellationException> { d.dial("id-0") }
    }
}
