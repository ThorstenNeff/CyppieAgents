package com.tneff.cyppieagents.connect

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-443 (PL-Auflage #2) — the **two trust axes stay separate**, enforced as a regression tripwire.
 *
 * The client has two INDEPENDENT trust axes that must NEVER run into each other:
 *  - **(a) Hub-trust** — TOFU pin of the hub's Noise static ([com.tneff.cyppieagents.net.hub.trust.TofuHubTrust] +
 *    [com.tneff.cyppieagents.net.hub.trust.PinnedHubStore]); built, carries.
 *  - **(b) Operator-identity** — the operator's device-key PoP ([com.tneff.cyppieagents.net.hub.operator.ClientOperatorAuth]);
 *    later BYOAuth (LDAP/AD) plugs in HERE.
 *
 * The invariant (Auflage #2): a future BYOAuth/LDAP operator-identity must **never "mitbenutzen" the TOFU pin** —
 * so the operator-auth code must not reach into the hub-pin/trust types, and the hub-trust code must not reach into
 * the operator-identity types. Today they are structurally separate (verified below); this guard **nails it down**
 * so a later reflex — wiring the pin store into operator-auth to reuse it — reddens instead of silently coupling
 * the axes. It scans production source (comments stripped) for a forbidden cross-axis type reference in EITHER
 * direction. It is NOT a substitute for the PO HALT on issuer/revocation decisions — it is the structural floor
 * under that boundary.
 */
class Cyp443TrustAxisSeparationGuardTest {

    /** Hub-trust-pin (axis a) type tokens that operator-auth (axis b) must never reference. */
    private val hubTrustPinTokens = listOf(
        "HubTrust", "TofuHubTrust", "PinnedHubStore", "TrustResolution", "PresentedHubKeySource",
    )

    /** Operator-identity (axis b) type tokens that hub-trust (axis a) must never reference. */
    private val operatorIdentityTokens = listOf(
        "ClientOperatorAuth", "OperatorAuthenticator", "OperatorPopBuilder", "CpJwtProvider",
        "OperatorDeviceKey", "CachingUserVerification", "OperatorSecretVault",
    )

    @Test
    fun operatorAuth_neverReferences_hubTrustPinAxis() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/net/hub/operator/ClientOperatorAuth.kt")
        for (token in hubTrustPinTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "Auflage #2: ClientOperatorAuth (operator-identity axis) must NOT reference the hub-trust-pin type " +
                    "'$token' — a BYOAuth/LDAP operator identity must never reuse the TOFU pin. Found a cross-axis reference.",
            )
        }
    }

    @Test
    fun hubTrust_neverReferences_operatorIdentityAxis() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/net/hub/trust/TofuHubTrust.kt")
        for (token in operatorIdentityTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "Auflage #2: TofuHubTrust (hub-trust axis) must NOT reference the operator-identity type '$token' — " +
                    "the hub pin must not depend on who the operator is. Found a cross-axis reference.",
            )
        }
    }

    /** Production source lines with comment/blank lines stripped, so a KDoc cross-reference in prose is not a false trip. */
    private fun codeLinesOf(rel: String): List<String> =
        locateSource(rel).readLines().filterNot { raw ->
            val t = raw.trimStart()
            t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
        }

    private fun locateSource(rel: String): File {
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isFile) return it }
            File(cur, "app/shared/$rel").let { if (it.isFile) return it }
            cur = cur.parentFile
        }
        error("could not locate $rel")
    }
}
