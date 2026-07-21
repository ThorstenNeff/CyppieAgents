package com.tneff.cyppieagents.transport

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-747 S1 (Step 1 — issuer CP→Relay repoint) — the hub's trusted-issuer anchor resolver (§5-C2 axis c
 * "hub trusts the issuer/relay"). ALL-OR-NOTHING per issuer: a COMPLETE relay set (Q3) is preferred; else a
 * COMPLETE CP set (deploy compat, additive/reversible); never a MIX of relay+CP fields. Both incomplete/absent →
 * null (fail-closed → InertRelayConnector). Every reject/choice is a non-vacuous, per-leg mutation-provable tooth.
 */
class Cyp747IssuerRepointTest {

    private val relayPub = Base64.getEncoder().encodeToString(ByteArray(32) { 0x11 })
    private val cpPub = Base64.getEncoder().encodeToString(ByteArray(32) { 0x22 })

    private fun envOf(m: Map<String, String>): (String) -> String? = { m[it] }

    @Test
    fun relaySet_preferred_overCp() {
        // A COMPLETE relay set is used even when a CP set is also present (Q3: the relay is the issuer now).
        // Mutation: resolve CP first (drop the relay preference) → returns the CP issuer → red.
        val anchor = RemoteRelayWiring.resolveIssuerAnchor(envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", "CYPPIE_RELAY_PUBKEY" to relayPub,
            "CYPPIE_CP_ISSUER" to "cp", "CYPPIE_CP_KID" to "c1", "CYPPIE_CP_PUBKEY" to cpPub,
        )))!!
        assertEquals("relay", anchor.issuer)
        assertEquals("r1", anchor.kid)
        assertEquals(0x11.toByte(), anchor.pub[0])
    }

    @Test
    fun noRelay_fallsBackToCp() {
        // No relay set → the complete CP set is the deploy-compat fallback. Mutation: drop the CP fallback → null → red.
        val anchor = RemoteRelayWiring.resolveIssuerAnchor(envOf(mapOf(
            "CYPPIE_CP_ISSUER" to "cp", "CYPPIE_CP_KID" to "c1", "CYPPIE_CP_PUBKEY" to cpPub,
        )))!!
        assertEquals("cp", anchor.issuer)
        assertEquals("c1", anchor.kid)
        assertEquals(0x22.toByte(), anchor.pub[0])
    }

    @Test
    fun partialRelay_neverMixesWithCp_usesCompleteCp() {
        // A relay set MISSING its pubkey is INCOMPLETE → the resolver must NOT mix (relay issuer + CP key = incoherent);
        // it falls through to the complete CP set. Mutation: per-field fallback (`RELAY_ISSUER ?: CP_ISSUER`, …) →
        // returns a MIXED {issuer=relay, kid=c1, …} → this asserts issuer=="cp", so the mix reddens (the anti-mix leg).
        val anchor = RemoteRelayWiring.resolveIssuerAnchor(envOf(mapOf(
            "CYPPIE_RELAY_ISSUER" to "relay", "CYPPIE_RELAY_KID" to "r1", // no RELAY_PUBKEY → relay set incomplete
            "CYPPIE_CP_ISSUER" to "cp", "CYPPIE_CP_KID" to "c1", "CYPPIE_CP_PUBKEY" to cpPub,
        )))!!
        assertEquals("cp", anchor.issuer, "an incomplete relay set must NOT mix with CP — fall through to the complete CP set")
        assertEquals("c1", anchor.kid)
    }

    @Test
    fun bothAbsentOrIncomplete_nullFailClosed() {
        assertNull(RemoteRelayWiring.resolveIssuerAnchor(envOf(emptyMap())), "no issuer → null (→ InertRelayConnector)")
        // Only an issuer name, no kid/pubkey → incomplete → null.
        assertNull(RemoteRelayWiring.resolveIssuerAnchor(envOf(mapOf("CYPPIE_RELAY_ISSUER" to "relay"))))
        // A blank value is treated as absent (takeIf isNotBlank).
        assertNull(RemoteRelayWiring.resolveIssuerAnchor(envOf(mapOf(
            "CYPPIE_CP_ISSUER" to "  ", "CYPPIE_CP_KID" to "c1", "CYPPIE_CP_PUBKEY" to cpPub,
        ))))
    }
}
