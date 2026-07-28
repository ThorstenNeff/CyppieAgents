package com.tneff.cyppieagents.model

import kotlinx.serialization.Serializable

/**
 * CYP-860 (S-Fed-1②, Epic CYP-832) — the ratified §5 trust DTOs + pure verification logic for cross-hub federation.
 * The Auftraggeber-binding architecture is **KEYSET rotation** (`{kid→pub}` + overlap window + old-key attestation),
 * NOT a single pin. All `:core`, `@ExperimentalFederation`; the actual Ed25519 math is a deferred [SignatureVerifier]
 * seam (wired server-side at the peer handshake, behind Epic §9.3) — here we build the DTO shapes + the trust logic
 * (which key, over what message, accept-either, fail-closed) and test them against a fake verifier, the same
 * seam-first pattern as the CYP-849/850/851 trio. **Nothing here arms anything.**
 */

/** One pinned issuer key: [kid] names it, [pub] is its base64 Ed25519 public key (opaque at this layer). */
@ExperimentalFederation
@Serializable
data class IssuerKey(val kid: String, val pub: String)

/**
 * The pinned issuer KEYSET (§5-2). During an overlap window it holds BOTH the old and the new key, so a peer
 * signature under EITHER is accepted ([accepts]) — rotation is not a flag-day. Empty keyset ⟹ nothing trusted
 * (fail-closed: [accepts] is `false`).
 */
@ExperimentalFederation
@Serializable
data class IssuerKeyset(val keys: List<IssuerKey>)

/**
 * A rotation attestation (§5-2): authorizes a NEW `(newKid, newPub)` with a [signature] made by the OLD key over
 * [rotationAttestationMessage]. Only an attestation an EXISTING keyset key actually signed may extend the keyset
 * ([IssuerKeyset.withRotation]).
 */
@ExperimentalFederation
@Serializable
data class RotationAttestation(val newKid: String, val newPub: String, val signature: String)

/**
 * A cross-hub revocation (§5-3): the issuer revokes [subject] (a `kid` or a `peerHubId`) with an issuer [signature].
 * Propagation + authority are the §5-3 freeze; this is the wire shape it travels in (extends the CYP-536 revoke
 * fan-out to the federation, = the still-open CYP-747 S4 C3).
 */
@ExperimentalFederation
@Serializable
data class FederationRevocation(val subject: String, val signature: String)

/**
 * The deferred signature-verification seam. The real impl is Ed25519 over the raw base64 [pubBase64] (server-side,
 * wired at the peer handshake); the trust logic below stubs against THIS so the "which key / accept-either /
 * fail-closed" decisions are testable without the crypto, and the crypto lives in exactly one place.
 */
@ExperimentalFederation
fun interface SignatureVerifier {
    fun verify(pubBase64: String, message: ByteArray, signatureBase64: String): Boolean
}

/**
 * The canonical message an issuer signs to attest a rotation — length-prefixed injective concat (the
 * `operatorAuthChallenge` discipline, CYP-473) of a domain tag + the new kid + the new pub, so no field boundary is
 * ambiguous. Distinct domain tag `"federation-rotation"` separates it from the peer PoP and the operator PoP.
 */
@ExperimentalFederation
fun rotationAttestationMessage(newKid: String, newPub: String): ByteArray =
    lengthPrefixedConcat(
        "federation-rotation".encodeToByteArray(),
        newKid.encodeToByteArray(),
        newPub.encodeToByteArray(),
    )

/**
 * CYP-863 (§5-3) — the canonical message an issuer signs to authorize a [FederationRevocation]. Length-prefixed
 * injective concat of a domain tag + the revoked subject (the CYP-473 discipline). The distinct tag
 * `"federation-revoke"` separates it from the rotation attestation ([rotationAttestationMessage]) and the peer PoP.
 */
@ExperimentalFederation
fun federationRevocationMessage(subject: String): ByteArray =
    lengthPrefixedConcat("federation-revoke".encodeToByteArray(), subject.encodeToByteArray())

/**
 * Overlap **accept-either**: a message+signature is accepted iff SOME key in the set verifies it. Fail-closed — an
 * empty keyset, or a signature no pinned key made, returns `false`.
 */
@ExperimentalFederation
fun IssuerKeyset.accepts(message: ByteArray, signatureBase64: String, verifier: SignatureVerifier): Boolean =
    keys.any { verifier.verify(it.pub, message, signatureBase64) }

/**
 * Apply a rotation. The new key is admitted into the keyset IFF an EXISTING (old) key actually signed the
 * attestation over [rotationAttestationMessage]. Returns the extended keyset (old ∪ new — the overlap window), or
 * `null` = **REJECTED, fail-closed** when no existing key authorized it (an unauthorized new key is never pinned).
 */
@ExperimentalFederation
fun IssuerKeyset.withRotation(attestation: RotationAttestation, verifier: SignatureVerifier): IssuerKeyset? {
    val message = rotationAttestationMessage(attestation.newKid, attestation.newPub)
    val authorized = keys.any { verifier.verify(it.pub, message, attestation.signature) }
    return if (authorized) IssuerKeyset(keys + IssuerKey(attestation.newKid, attestation.newPub)) else null
}

/**
 * Partition posture (§5-4) → the CYP-849 axis. When the issuer is UNREACHABLE a hub cannot confirm anchor/revoke
 * state, so federated identities collapse to [HubIssuerTrust.NOT_TRUSTED] — which [IssuerTrustFederationDecider]
 * (CYP-849) then DENIES. It may over-deny during a partition; it never over-admits. When reachable, the resolved
 * posture stands.
 */
@ExperimentalFederation
fun issuerTrustUnderPartition(issuerReachable: Boolean, whenReachable: HubIssuerTrust): HubIssuerTrust =
    if (issuerReachable) whenReachable else HubIssuerTrust.NOT_TRUSTED

/** 4-byte big-endian length-prefixed concat — injective, so no field boundary is ambiguous (CYP-473 discipline). */
private fun lengthPrefixedConcat(vararg fields: ByteArray): ByteArray {
    val out = ByteArray(fields.sumOf { 4 + it.size })
    var i = 0
    for (f in fields) {
        out[i++] = (f.size ushr 24).toByte()
        out[i++] = (f.size ushr 16).toByte()
        out[i++] = (f.size ushr 8).toByte()
        out[i++] = f.size.toByte()
        f.copyInto(out, i)
        i += f.size
    }
    return out
}
