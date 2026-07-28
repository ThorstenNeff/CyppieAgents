package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.FederationRevocation
import com.tneff.cyppieagents.model.HubIssuerTrust
import com.tneff.cyppieagents.model.IssuerKey
import com.tneff.cyppieagents.model.IssuerKeyset
import com.tneff.cyppieagents.model.IssuerTrustFederationDecider
import com.tneff.cyppieagents.model.FederationTrustDecision
import com.tneff.cyppieagents.model.RotationAttestation
import com.tneff.cyppieagents.model.SignatureVerifier
import com.tneff.cyppieagents.model.accepts
import com.tneff.cyppieagents.model.issuerTrustUnderPartition
import com.tneff.cyppieagents.model.rotationAttestationMessage
import com.tneff.cyppieagents.model.withRotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-860 (S-Fed-1②) — teeth for the ratified §5 keyset-rotation trust DTOs + verification logic, stubbed against a
 * fake [SignatureVerifier] (the real Ed25519 is a deferred seam). Block bodies throughout.
 */
@OptIn(ExperimentalFederation::class)
class Cyp860FederationTrustDtosTest {

    private val OLD = IssuerKey(kid = "k1", pub = "PUB_OLD")
    private val NEW = IssuerKey(kid = "k2", pub = "PUB_NEW")

    /** A fake verifier that accepts a signature only for an explicit (pub, signature) allow-list. */
    private fun verifierAccepting(vararg pairs: Pair<String, String>): SignatureVerifier {
        val allow = pairs.toSet()
        return SignatureVerifier { pub, _, sig -> (pub to sig) in allow }
    }

    // --- overlap: accept-either ---------------------------------------------------------------------------------

    @Test
    fun overlapKeyset_acceptsSignatureUnderEitherKey() {
        val keyset = IssuerKeyset(listOf(OLD, NEW))
        val msg = "hello".encodeToByteArray()
        // a sig only the NEW key made → accepted (via overlap)
        assertTrue(keyset.accepts(msg, "SIG_NEW", verifierAccepting("PUB_NEW" to "SIG_NEW")))
        // a sig only the OLD key made → still accepted during the overlap window
        assertTrue(keyset.accepts(msg, "SIG_OLD", verifierAccepting("PUB_OLD" to "SIG_OLD")))
    }

    @Test
    fun keyset_rejectsWhenNoPinnedKeyVerifies_failClosed() {
        val keyset = IssuerKeyset(listOf(OLD, NEW))
        assertFalse(keyset.accepts("m".encodeToByteArray(), "SIG_X", verifierAccepting("PUB_STRANGER" to "SIG_X")))
        // empty keyset trusts nothing
        assertFalse(IssuerKeyset(emptyList()).accepts("m".encodeToByteArray(), "SIG_OLD", verifierAccepting("PUB_OLD" to "SIG_OLD")))
    }

    // --- rotation: old key signs new ----------------------------------------------------------------------------

    @Test
    fun rotation_authorizedByExistingKey_extendsKeysetWithOverlap() {
        val keyset = IssuerKeyset(listOf(OLD))
        val att = RotationAttestation(newKid = "k2", newPub = "PUB_NEW", signature = "ATT_SIG")
        // the OLD key signed the attestation message → authorized
        val verifier = SignatureVerifier { pub, msg, sig ->
            pub == "PUB_OLD" && sig == "ATT_SIG" && msg.contentEquals(rotationAttestationMessage("k2", "PUB_NEW"))
        }
        val rotated = keyset.withRotation(att, verifier)
        assertNotNull(rotated)
        assertEquals(listOf(OLD, IssuerKey("k2", "PUB_NEW")), rotated.keys) // old ∪ new = overlap
    }

    @Test
    fun rotation_notSignedByAnyExistingKey_rejectedFailClosed() {
        val keyset = IssuerKeyset(listOf(OLD))
        val att = RotationAttestation(newKid = "k2", newPub = "PUB_NEW", signature = "ATT_SIG")
        // the attestation was NOT signed by any pinned key (verifier accepts only a stranger) → reject, unchanged
        val verifier = verifierAccepting("PUB_STRANGER" to "ATT_SIG")
        assertNull(keyset.withRotation(att, verifier), "unauthorized rotation must be rejected fail-closed")
    }

    /** The self-signing loophole: an attestation "signed" by the NEW key it introduces must NOT authorize itself. */
    @Test
    fun rotation_selfSignedByNewKey_rejected() {
        val keyset = IssuerKeyset(listOf(OLD))
        val att = RotationAttestation(newKid = "k2", newPub = "PUB_NEW", signature = "SELF")
        val verifier = verifierAccepting("PUB_NEW" to "SELF") // only the incoming NEW key "verifies" — not pinned
        assertNull(keyset.withRotation(att, verifier), "a rotation may only be authorized by an EXISTING pinned key")
    }

    // --- rotation message injectivity ---------------------------------------------------------------------------

    @Test
    fun rotationAttestationMessage_isInjective() {
        // different (kid,pub) must yield different signed bytes — no boundary-collision (a‖b vs a'‖b').
        assertFalse(rotationAttestationMessage("k", "ab").contentEquals(rotationAttestationMessage("ka", "b")))
        assertFalse(rotationAttestationMessage("k1", "p").contentEquals(rotationAttestationMessage("k2", "p")))
    }

    // --- partition → CYP-849 DENY -------------------------------------------------------------------------------

    @Test
    fun partition_collapsesToNotTrusted_thenCyp849Denies() {
        // unreachable issuer → NOT_TRUSTED regardless of the reachable posture
        assertEquals(HubIssuerTrust.NOT_TRUSTED, issuerTrustUnderPartition(false, HubIssuerTrust.TRUSTED))
        // …and CYP-849 then DENIES it
        assertEquals(
            FederationTrustDecision.DENY,
            IssuerTrustFederationDecider.decide(issuerTrustUnderPartition(false, HubIssuerTrust.TRUSTED)),
        )
        // reachable → the resolved posture stands (positive control: a TRUSTED anchor still admits)
        assertEquals(HubIssuerTrust.TRUSTED, issuerTrustUnderPartition(true, HubIssuerTrust.TRUSTED))
        assertEquals(
            FederationTrustDecision.ADMIT,
            IssuerTrustFederationDecider.decide(issuerTrustUnderPartition(true, HubIssuerTrust.TRUSTED)),
        )
    }

    // --- wire DTO roundtrip -------------------------------------------------------------------------------------

    @Test
    fun revocationAndKeyset_roundTrip() {
        val rev = FederationRevocation(subject = "k1", signature = "REV_SIG")
        assertEquals(rev, CommJson.decodeFromString(FederationRevocation.serializer(), CommJson.encodeToString(FederationRevocation.serializer(), rev)))
        val ks = IssuerKeyset(listOf(OLD, NEW))
        assertEquals(ks, CommJson.decodeFromString(IssuerKeyset.serializer(), CommJson.encodeToString(IssuerKeyset.serializer(), ks)))
    }
}
