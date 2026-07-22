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

    /**
     * CYP-747 §5-C2 — the **issuer-trust axis (c)**: whether the hub's CP-JWT/cert ISSUER is established/trusted. A
     * THIRD axis, orthogonal to (a) hub-key TOFU and (b) operator identity — an untrusted issuer grants no authority
     * regardless of a valid pin or a valid operator. Neither (a) nor (b) may reach into it (the issuer anchor/pin-home
     * must be its OWN store, never reuse the TOFU pin or the operator vault). The anchor-determination itself lands in
     * Backend-S1 wiring; this pins the a/b ⊥ c separation NOW (the symmetric "issuer-home ⊥ a/b" scan lands with that
     * file). Tokens are the issuer-axis type names (the UI failure today + the anticipated S1 anchor/pin types).
     */
    private val issuerTrustTokens = listOf(
        "IssuerNotTrusted", "IssuerAnchor", "IssuerTrust", "IssuerPinStore", "CpIssuerPin",
    )

    /** CYP-798 §4b — the SHARED `:core` hub-trust VOCABULARY (axis-a UX enums: [com.tneff.cyppieagents.model.HubTrustState]
     *  etc.). It must stay a pure vocabulary — never reach into the operator-identity (b) or issuer (c) axes, and no
     *  `:server`-issuer coupling — so folding the axes can't leak in via the shared enum (the Reviewer ① build tooth). */
    private val sharedHubTrustEnumTokens = listOf("HubTrustState", "TrustRejectReason", "HubDescriptorValidity")

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

    @Test
    fun hubTrust_neverReferences_issuerAxis() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/net/hub/trust/TofuHubTrust.kt")
        for (token in issuerTrustTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-747 §5-C2: TofuHubTrust (hub-trust axis a) must NOT reference the issuer-trust type '$token' — the " +
                    "issuer axis (c) is orthogonal; the hub pin must not double as issuer trust. Found a cross-axis reference.",
            )
        }
    }

    @Test
    fun operatorAuth_neverReferences_issuerAxis() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/net/hub/operator/ClientOperatorAuth.kt")
        for (token in issuerTrustTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-747 §5-C2: ClientOperatorAuth (operator-identity axis b) must NOT reference the issuer-trust type " +
                    "'$token' — operator identity does not establish issuer trust. Found a cross-axis reference.",
            )
        }
    }

    @Test
    fun issuerHome_neverReferences_hubTrustPinOrOperatorAxes() {
        // CYP-802 (CYP-747 S1c) — the SYMMETRIC scan the issuer-axis KDoc above anticipated ("the symmetric
        // 'issuer-home ⊥ a/b' scan lands with that file"). IssuerTrustCheck is the FIRST real axis-c client home
        // (the issuer-trust seam + the client-produce mapping). It must not reach into axis-a (hub-key TOFU pin) or
        // axis-b (operator identity) — the issuer anchor is its OWN axis, never reusing the TOFU pin or operator vault.
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/net/hub/issuer/IssuerTrustCheck.kt")
        for (token in hubTrustPinTokens + operatorIdentityTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-747 §5-C2: the issuer-trust axis-c home (IssuerTrustCheck) must NOT reference the hub-trust-pin (a) " +
                    "/ operator-identity (b) type '$token' — the issuer axis is orthogonal; it must not reuse the TOFU pin " +
                    "or the operator vault. Found a cross-axis reference.",
            )
        }
    }

    @Test
    fun sharedHubTrustEnum_neverReferences_operatorOrIssuerAxes() {
        // CYP-798 §4b (Reviewer ①) — module-boundary tooth: the shared `:core` HubTrust vocabulary lives OUTSIDE
        // :app:shared, so a repo-root-relative path (locateSource walks up to the repo root, which holds `core/`).
        val code = codeLinesOf("core/src/commonMain/kotlin/com/tneff/cyppieagents/model/HubTrust.kt")
        for (token in operatorIdentityTokens + issuerTrustTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-798 §4b: the shared :core HubTrust vocabulary (axis a) must NOT reference the operator-identity (b) " +
                    "/ issuer (c) type '$token' — the shared trust enum stays a pure vocabulary, no :server-issuer coupling. " +
                    "Found a cross-axis reference.",
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
