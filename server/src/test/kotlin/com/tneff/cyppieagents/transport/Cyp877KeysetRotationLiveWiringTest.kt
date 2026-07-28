package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.ExperimentalFederation
import com.tneff.cyppieagents.model.IssuerKey
import com.tneff.cyppieagents.model.RotationAttestation
import com.tneff.cyppieagents.model.accepts
import com.tneff.cyppieagents.model.rotationAttestationMessage
import kotlinx.serialization.builtins.ListSerializer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-877 (S-Fed, Epic CYP-832, DARK) — the trusted-issuer KEYSET live-wiring: `resolveIssuerAnchor`'s single pin
 * becomes a rotatable `IssuerKeyset {kid→pub}` (§5-2 overlap window) resolved for the live path, with the
 * `resolveIssuerAnchor` establishment view single-sourced from it (no drift) and an optional rotation attestation
 * applied fail-closed. Everything here is pure resolution/logic against a fake env + REAL Ed25519 — **no connect, no
 * transport, no arming** (the live path stays INERT by default).
 */
@OptIn(ExperimentalFederation::class)
class Cyp877KeysetRotationLiveWiringTest {

    private fun envOf(m: Map<String, String>): (String) -> String? = { m[it] }
    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun keysetJson(vararg keys: IssuerKey): String =
        CommJson.encodeToString(ListSerializer(IssuerKey.serializer()), keys.toList())

    // REAL Ed25519 issuer keypairs — k1/k2 pinned, kX an outsider.
    private val k1 = RawKeys.generateEd25519()
    private val k2 = RawKeys.generateEd25519()
    private val kX = RawKeys.generateEd25519()
    private val k1pub = b64(k1.publicRaw)
    private val k2pub = b64(k2.publicRaw)

    // ── Resolution: single-pin backward-compat ────────────────────────────────────────────────────────────────────

    /**
     * ★ Backward-compat: a legacy single-pin env collapses to a **keyset of ONE**, and `resolveIssuerAnchor`
     * (derived from the keyset) is byte-identical to the pre-CYP-877 anchor. This is the Auftraggeber single-pin
     * override path — it must stay exactly as it was.
     */
    @Test
    fun singlePin_collapsesToKeysetOfOne_anchorDerivedIdentical() {
        val env = envOf(mapOf("CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to k1pub))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        assertEquals("relay", resolved.issuer)
        assertEquals(listOf(IssuerKey("r1", k1pub)), resolved.keyset.keys, "single pin ⟹ keyset of one")
        assertEquals("CYPPIE_RELAY_", resolved.envPrefix)
        val anchor = assertNotNull(RemoteRelayWiring.resolveIssuerAnchor(env))
        assertEquals("relay", anchor.issuer)
        assertEquals("r1", anchor.kid)
        assertContentEquals(k1.publicRaw, anchor.pub)
    }

    private fun assertContentEquals(a: ByteArray, b: ByteArray) = assertTrue(a.contentEquals(b))

    // ── Resolution: multi-key keyset (the overlap window) ─────────────────────────────────────────────────────────

    /**
     * A multi-key `*_KEYSET` resolves the overlap window; the derived anchor exposes the FIRST key (establishment
     * view) while BOTH pinned kids resolve for the live per-`kid` lookup — a peer signed under either is admitted.
     */
    @Test
    fun multiKeyKeyset_resolvesOverlapWindow_bothKidsResolve() {
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay",
            "CYPPIE_RELAY_KEYSET" to keysetJson(IssuerKey("r1", k1pub), IssuerKey("r2", k2pub)),
        ))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        assertEquals(2, resolved.keyset.keys.size)
        // live per-kid lookup (what buildRemoteTransport's cpPublicKey does) resolves BOTH pinned kids, nothing else.
        assertEquals(k1pub, resolved.keyset.keys.firstOrNull { it.kid == "r1" }?.pub)
        assertEquals(k2pub, resolved.keyset.keys.firstOrNull { it.kid == "r2" }?.pub)
        assertNull(resolved.keyset.keys.firstOrNull { it.kid == "unknown" }, "an unknown kid resolves to nothing (fail-closed)")
        // anchor establishment view = first key.
        val anchor = assertNotNull(RemoteRelayWiring.resolveIssuerAnchor(env))
        assertEquals("r1", anchor.kid)
    }

    /** Within a set, the multi-key `*_KEYSET` is PREFERRED over a co-present single pin. */
    @Test
    fun keysetPreferredOverSinglePin_withinSet() {
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay",
            "CYPPIE_RELAY_KID" to "solo", "CYPPIE_RELAY_PUBKEY" to k1pub,
            "CYPPIE_RELAY_KEYSET" to keysetJson(IssuerKey("r1", k1pub), IssuerKey("r2", k2pub)),
        ))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        assertEquals(listOf("r1", "r2"), resolved.keyset.keys.map { it.kid }, "the multi-key keyset wins over the single pin")
    }

    /** Relay set preferred; an INCOMPLETE relay set never mixes with CP — it falls through to a complete CP set. */
    @Test
    fun relayPreferred_incompleteRelayFallsToCp_noMix() {
        // relay has ISSUER but no keyset and no complete single pin (missing PUBKEY) → incomplete → fall to CP.
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", // no PUBKEY, no KEYSET
            "CYPPIE_CP_ISSUER" to "cp", "CYPPIE_CP_KEYSET" to keysetJson(IssuerKey("c1", k2pub)),
        ))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        assertEquals("cp", resolved.issuer, "an incomplete relay set must NOT mix — fall through to the complete CP set")
        assertEquals("CYPPIE_CP_", resolved.envPrefix)
        assertEquals(listOf("c1"), resolved.keyset.keys.map { it.kid })
    }

    // ── Resolution: fail-closed ───────────────────────────────────────────────────────────────────────────────────

    /** ★ Fail-closed: absent, empty keyset, malformed JSON, or ANY key with a blank/undecodable field ⟹ null. */
    @Test
    fun failClosed_absentEmptyMalformedBadKey_null() {
        assertNull(RemoteRelayWiring.resolveIssuerKeyset(envOf(emptyMap())), "absent → null")
        assertNull(
            RemoteRelayWiring.resolveIssuerKeyset(envOf(mapOf("CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KEYSET" to "[]"))),
            "empty keyset → null (nothing trusted)",
        )
        assertNull(
            RemoteRelayWiring.resolveIssuerKeyset(envOf(mapOf("CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KEYSET" to "not-json"))),
            "malformed JSON → null",
        )
        assertNull(
            RemoteRelayWiring.resolveIssuerKeyset(envOf(mapOf(
                "CYPPIE_RELAY_ISSUER" to "relay",
                "CYPPIE_RELAY_KEYSET" to keysetJson(IssuerKey("r1", k1pub), IssuerKey("bad", "!!not-base64!!")),
            ))),
            "one undecodable key ⟹ the whole keyset is rejected (never silently pruned)",
        )
        assertNull(
            RemoteRelayWiring.resolveIssuerKeyset(envOf(mapOf("CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1"))),
            "single pin missing PUBKEY → null",
        )
    }

    // ── Overlap accept-either under REAL Ed25519 ──────────────────────────────────────────────────────────────────

    /** The resolved overlap keyset accepts a peer signature under ANY pinned key (real Ed25519); an outsider's is rejected. */
    @Test
    fun acceptsOverlap_peerSignedUnderAnyPinnedKey_realEd25519() {
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay",
            "CYPPIE_RELAY_KEYSET" to keysetJson(IssuerKey("r1", k1pub), IssuerKey("r2", k2pub)),
        ))
        val keyset = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env)).keyset
        val msg = "peer-attestation".encodeToByteArray()
        assertTrue(keyset.accepts(msg, b64(RawKeys.ed25519Sign(k1.privateRaw, msg)), Ed25519SignatureVerifier), "signed by r1 (in window) → accepted")
        assertTrue(keyset.accepts(msg, b64(RawKeys.ed25519Sign(k2.privateRaw, msg)), Ed25519SignatureVerifier), "signed by r2 (in window) → accepted")
        assertFalse(keyset.accepts(msg, b64(RawKeys.ed25519Sign(kX.privateRaw, msg)), Ed25519SignatureVerifier), "signed by an outsider → REJECTED")
    }

    // ── Rotation at the live path ─────────────────────────────────────────────────────────────────────────────────

    private fun attestation(newKid: String, newPub: String, signerSeed: ByteArray): String {
        val sig = b64(RawKeys.ed25519Sign(signerSeed, rotationAttestationMessage(newKid, newPub)))
        return CommJson.encodeToString(RotationAttestation.serializer(), RotationAttestation(newKid, newPub, sig))
    }

    /** No rotation env → the base keyset is unchanged. */
    @Test
    fun rotationAbsent_baseUnchanged() {
        val env = envOf(mapOf("CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to k1pub))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        val eff = RemoteRelayWiring.applyRotationAtLivePath(env, resolved, Ed25519SignatureVerifier)
        assertEquals(listOf(IssuerKey("r1", k1pub)), eff.keys)
    }

    /** A rotation attested by an EXISTING key extends the overlap window — the new kid then resolves + verifies. */
    @Test
    fun rotationAttestedByExistingKey_extendsWindow_newKidResolves() {
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to k1pub,
            "CYPPIE_RELAY_ROTATION" to attestation("r2", k2pub, signerSeed = k1.privateRaw), // k1 (existing) authorizes k2
        ))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        val eff = RemoteRelayWiring.applyRotationAtLivePath(env, resolved, Ed25519SignatureVerifier)
        assertEquals(listOf("r1", "r2"), eff.keys.map { it.kid }, "old ∪ new (overlap window)")
        val msg = "post-rotation".encodeToByteArray()
        assertTrue(eff.accepts(msg, b64(RawKeys.ed25519Sign(k2.privateRaw, msg)), Ed25519SignatureVerifier), "the newly-rotated key now verifies")
    }

    /**
     * ★ CENTRAL fail-closed tooth: a FORGED rotation (the new key attested by an OUTSIDER, not an existing keyset key)
     * is REJECTED under real Ed25519 — the base keyset is unchanged and the forged kid NEVER resolves. An unauthorized
     * key can never be pinned via rotation.
     */
    @Test
    fun rotationForgedByOutsider_rejected_baseUnchanged_forgedKidNeverResolves() {
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to k1pub,
            "CYPPIE_RELAY_ROTATION" to attestation("evil", b64(kX.publicRaw), signerSeed = kX.privateRaw), // outsider self-authorizes
        ))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        val eff = RemoteRelayWiring.applyRotationAtLivePath(env, resolved, Ed25519SignatureVerifier)
        assertEquals(listOf(IssuerKey("r1", k1pub)), eff.keys, "forged rotation REJECTED — base keyset unchanged")
        assertNull(eff.keys.firstOrNull { it.kid == "evil" }, "the forged kid is never pinned")
    }

    /** A malformed rotation JSON is ignored fail-closed — the base keyset is unchanged. */
    @Test
    fun rotationMalformedJson_ignored_baseUnchanged() {
        val env = envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to k1pub,
            "CYPPIE_RELAY_ROTATION" to "not-a-valid-attestation",
        ))
        val resolved = assertNotNull(RemoteRelayWiring.resolveIssuerKeyset(env))
        val eff = RemoteRelayWiring.applyRotationAtLivePath(env, resolved, Ed25519SignatureVerifier)
        assertEquals(listOf(IssuerKey("r1", k1pub)), eff.keys)
    }
}
