package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.FederationFrame
import com.tneff.cyppieagents.model.FederationRevocation
import com.tneff.cyppieagents.model.FederationRevocationFrame
import com.tneff.cyppieagents.model.IssuerKey
import com.tneff.cyppieagents.model.IssuerKeyset
import com.tneff.cyppieagents.model.SignatureVerifier
import com.tneff.cyppieagents.model.federationRevocationMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-863 (S-Fed-6, §5-3, DARK) — teeth for the cross-hub revoke active fanout + the ratified revoke wire.
 */
@OptIn(ExperimentalFederation::class)
class Cyp863FederationRevokeFanoutTest {

    private class RecordingTransport : FederationPeerTransport {
        val sent = mutableListOf<ByteArray>()
        var closed = 0
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun send(frame: ByteArray) { sent += frame }
        override suspend fun close() { closed++ }
    }

    // issuer keyset pins "PUB"; the fake verifier accepts sig "GOOD" over the correct revocation message for a subject.
    private val keyset = IssuerKeyset(listOf(IssuerKey(kid = "k1", pub = "PUB")))
    private fun verifierFor(subject: String) = SignatureVerifier { pub, msg, sig ->
        pub == "PUB" && sig == "GOOD" && msg.contentEquals(federationRevocationMessage(subject))
    }

    /** A verified revocation fans out to the MATCHING peer (send + close); a non-matching peer is untouched. */
    @Test
    fun verifiedRevocation_fansOutToMatchingPeerOnly() = runBlocking {
        val tB = RecordingTransport()
        val tC = RecordingTransport()
        val peers = listOf(FederationPeer("peerB", tB), FederationPeer("peerC", tC))
        val fanout = FederationRevocationFanout(keyset, verifierFor("peerB"))

        val revoked = fanout.fanout(FederationRevocation(subject = "peerB", signature = "GOOD"), peers)

        assertEquals(listOf("peerB"), revoked)
        assertEquals(1, tB.sent.size); assertEquals(1, tB.closed) // matching peer notified + torn down
        assertEquals(0, tC.sent.size); assertEquals(0, tC.closed) // non-matching untouched
    }

    /** Fail-closed: a revocation NOT signed by the pinned issuer fans out to NOBODY (no send, no close on any peer). */
    @Test
    fun unverifiedRevocation_fansOutToNobody_failClosed() = runBlocking {
        val tB = RecordingTransport()
        val peers = listOf(FederationPeer("peerB", tB))
        val fanout = FederationRevocationFanout(keyset, verifierFor("peerB"))

        val revoked = fanout.fanout(FederationRevocation(subject = "peerB", signature = "FORGED"), peers)

        assertEquals(emptyList(), revoked)
        assertEquals(0, tB.sent.size); assertEquals(0, tB.closed)
    }

    /** Among many peers, only those whose id equals the revoked subject are torn down. */
    @Test
    fun fanout_matchesSubjectId_leavesOthers() = runBlocking {
        val transports = List(3) { RecordingTransport() }
        val peers = listOf(
            FederationPeer("peerA", transports[0]),
            FederationPeer("peerB", transports[1]),
            FederationPeer("peerZ", transports[2]),
        )
        val fanout = FederationRevocationFanout(keyset, verifierFor("peerB"))

        fanout.fanout(FederationRevocation(subject = "peerB", signature = "GOOD"), peers)

        assertEquals(0, transports[0].closed)
        assertEquals(1, transports[1].closed)
        assertEquals(0, transports[2].closed)
    }

    /**
     * M2-FREEZE CONFORMANCE (byte-exact vs §5-3): the revoke travels as the ratified [FederationRevocationFrame] under
     * the CommJson `type`-discriminator `"revoke"`, and round-trips byte-exact — a drift in the frame shape reds here.
     */
    @Test
    fun revocationFrame_roundTripsByteExact_m2Conformance() {
        val frame: FederationFrame = FederationRevocationFrame(FederationRevocation(subject = "peerB", signature = "sig123"))
        val json = CommJson.encodeToString(FederationFrame.serializer(), frame)
        assertTrue("\"type\":\"revoke\"" in json, "revoke must carry the ratified type-discriminator: $json")
        assertEquals(frame, CommJson.decodeFromString(FederationFrame.serializer(), json))
    }
}
