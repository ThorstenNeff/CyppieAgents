package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.FederationFrame
import com.tneff.cyppieagents.model.FederationHello
import com.tneff.cyppieagents.model.FederationRevocation
import com.tneff.cyppieagents.model.FederationRevocationFrame
import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.model.IssuerKey
import com.tneff.cyppieagents.model.IssuerKeyset
import com.tneff.cyppieagents.model.federationRevocationMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-871 — the **dark dress rehearsal**: proves the COMPOSITION of all six dark pieces against stubs, so arming
 * later is a pure stub→live swap. The chain: admit (CYP-850/858 guard) → FederationSession.open (CYP-858) →
 * FederationTunnelTransport over a stub ServerNoiseTunnel (CYP-864) → FederationFrame wire (CYP-859) → issuer
 * keyset + REAL Ed25519 (CYP-860/862) → revoke fanout (CYP-863). Everything runs against a stub tunnel — no live
 * connect/transport/send anywhere.
 */
@OptIn(ExperimentalFederation::class)
class Cyp871DarkBatchIntegrationTest {

    private class StubTunnel(
        outbound: List<ByteArray> = emptyList(),
        override val handshakeHash: ByteArray = ByteArray(32) { it.toByte() },
    ) : ServerNoiseTunnel {
        val sent = mutableListOf<ByteArray>()
        private val inbound = ArrayDeque(outbound)
        var closed = 0
        override suspend fun send(plaintext: ByteArray) { sent += plaintext }
        override suspend fun receive(): ByteArray? = inbound.removeFirstOrNull()
        override suspend fun close() { closed++ }
    }

    // A REAL Ed25519 issuer keypair (CYP-862) anchoring the trust chain (CYP-860 keyset).
    private val issuer = RawKeys.generateEd25519()
    private val issuerKeyset =
        IssuerKeyset(listOf(IssuerKey(kid = "k1", pub = Base64.getEncoder().encodeToString(issuer.publicRaw))))

    private fun frameBytes(frame: FederationFrame): ByteArray =
        CommJson.encodeToString(FederationFrame.serializer(), frame).encodeToByteArray()

    private fun decodeFrame(bytes: ByteArray): FederationFrame =
        CommJson.decodeFromString(FederationFrame.serializer(), bytes.decodeToString())

    /**
     * ★ CENTRAL TOOTH — admit-DENY ⟹ no session ⟹ no frame. The CYP-858 guard holds the ENTIRE assembled chain
     * inert: with the arming posture off, `open` yields no session, so nothing is ever transmitted over the tunnel.
     * This is what makes the dark batch physically incapable of a live exchange until arming.
     */
    @Test
    fun deniedAdmission_noSession_noFrame_assemblyInert() = runBlocking {
        val stub = StubTunnel()
        val transport = FederationTunnelTransport(stub)
        val disabledGate = FederationAdmissionGate(federationEnabled = false) // arming posture OFF
        val session = FederationSession.open(disabledGate, HubIssuerTrust.TRUSTED, transport)
        assertNull(session, "denied admission must yield NO session")
        assertEquals(0, stub.sent.size, "with no session, nothing can ever reach the tunnel")
    }

    /**
     * admit-ADMIT ⟹ a FederationHello round-trips END-TO-END through the composed chain: session → tunnel-transport →
     * stub tunnel (byte-exact) outbound, and stub tunnel → tunnel-transport → session.incoming inbound.
     */
    @Test
    fun admittedAdmission_helloRoundTripsEndToEnd() = runBlocking {
        val hello: FederationFrame = FederationHello(protoMin = 1, protoMax = 3)
        val wire = frameBytes(hello)
        val stub = StubTunnel(outbound = listOf(wire))
        val session = assertNotNull(
            FederationSession.open(FederationAdmissionGate(federationEnabled = true), HubIssuerTrust.TRUSTED, FederationTunnelTransport(stub)),
        )
        // outbound: session.send → tunnel, byte-exact + decodes back
        session.send(wire)
        assertContentEquals(wire, stub.sent.single())
        assertEquals(hello, decodeFrame(stub.sent.single()))
        // inbound: tunnel → session.incoming, decodes back
        assertEquals(hello, decodeFrame(session.incoming.toList().single()))
    }

    /**
     * revoke-in-flow ⟹ teardown: a REAL-Ed25519-signed issuer revocation fans out over the peer's tunnel-transport as
     * the ratified [FederationRevocationFrame] and tears the peer down. Composes CYP-863 + 864 + 862 + 860.
     */
    @Test
    fun revokeInFlow_tearsDownPeerOverTunnelTransport() = runBlocking {
        val subject = "peerB"
        val sig = Base64.getEncoder().encodeToString(RawKeys.ed25519Sign(issuer.privateRaw, federationRevocationMessage(subject)))
        val revocation = FederationRevocation(subject = subject, signature = sig)
        val stub = StubTunnel()
        val peer = FederationPeer(subject, FederationTunnelTransport(stub))

        val revoked = FederationRevocationFanout(issuerKeyset, Ed25519SignatureVerifier).fanout(revocation, listOf(peer))

        assertEquals(listOf(subject), revoked)
        assertEquals(1, stub.sent.size, "revoke frame pushed over the tunnel")
        assertEquals(1, stub.closed, "peer torn down")
        assertEquals(FederationRevocationFrame(revocation), decodeFrame(stub.sent.single()))
    }

    /**
     * dark-boundary under REAL crypto: a FORGED revocation (wrong signature, verified by the real Ed25519 verifier)
     * fans out to NOBODY — the trust gate holds fail-closed even in the fully-composed assembly.
     */
    @Test
    fun forgedRevoke_underRealCrypto_fansOutToNobody() = runBlocking {
        val stub = StubTunnel()
        val peer = FederationPeer("peerB", FederationTunnelTransport(stub))
        val forged = FederationRevocation("peerB", Base64.getEncoder().encodeToString(ByteArray(64))) // all-zero sig

        val revoked = FederationRevocationFanout(issuerKeyset, Ed25519SignatureVerifier).fanout(forged, listOf(peer))

        assertEquals(emptyList(), revoked)
        assertEquals(0, stub.sent.size)
        assertEquals(0, stub.closed)
    }
}
