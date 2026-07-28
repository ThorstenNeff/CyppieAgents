package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.FederationFrame
import com.tneff.cyppieagents.model.FederationRevocation
import com.tneff.cyppieagents.model.FederationRevocationFrame
import com.tneff.cyppieagents.model.IssuerKeyset
import com.tneff.cyppieagents.model.SignatureVerifier
import com.tneff.cyppieagents.model.accepts
import com.tneff.cyppieagents.model.federationRevocationMessage

/** A registered federation peer: its hub id + the (dark/stub) transport a revoke would be pushed over. */
@ExperimentalFederation
class FederationPeer(val peerHubId: String, val transport: FederationPeerTransport)

/**
 * CYP-863 (S-Fed-6, §5-3, DARK) — the cross-hub revoke **active fanout**. Extends the CYP-536 intra-hub
 * `TunnelSessionRegistry.revokeOperator` fan-out to the federation: a VERIFIED issuer revocation is pushed to every
 * peer whose id matches the revoked subject — over that peer's transport, as the ratified [FederationRevocationFrame]
 * (the §5-3 propagation channel) — and the peer is then torn down.
 *
 * **Fail-closed:** a revocation NOT signed by the pinned issuer [keyset] (overlap accept-either, CYP-860) fans out to
 * NOBODY — an unauthenticated revoke can neither tear a peer down nor be propagated. The issuer signs
 * [federationRevocationMessage] over the subject.
 *
 * **DARK:** [FederationPeerTransport] is the stub seam — this pushes bytes over it but nothing connects; live transmit
 * over a real Noise tunnel is arming-gated (§9.3 + server re-auth + GO). No live-send.
 */
@ExperimentalFederation
class FederationRevocationFanout(
    private val keyset: IssuerKeyset,
    private val verifier: SignatureVerifier,
) {
    /**
     * Fan [revocation] out to matching [peers]. Returns the ids that were revoked (torn down + notified). Fail-closed:
     * an unverifiable revocation returns an empty list and touches NO peer.
     */
    suspend fun fanout(revocation: FederationRevocation, peers: List<FederationPeer>): List<String> {
        if (!keyset.accepts(federationRevocationMessage(revocation.subject), revocation.signature, verifier)) {
            return emptyList()
        }
        val frame = CommJson.encodeToString(FederationFrame.serializer(), FederationRevocationFrame(revocation))
            .encodeToByteArray()
        val revoked = mutableListOf<String>()
        for (peer in peers) {
            if (peer.peerHubId == revocation.subject) {
                peer.transport.send(frame) // active fanout over the (stub) transport — §5-3 propagation
                peer.transport.close()     // tear the peer down
                revoked += peer.peerHubId
            }
        }
        return revoked
    }
}
