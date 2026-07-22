package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.model.HubIssuerTrust
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * CYP-441 (S-C / R3, F3) — a hub **registration** at the Control Plane, carrying both raw public keys and a
 * **mandatory Proof-of-Possession**. The PoP is an Ed25519 signature by the hub's signing key over a CP-issued
 * nonce (bound to the approved device-code session — that binding is the Reviewer/S-D strand). Without a valid PoP
 * a substituted public key could poison the entire Phase-2 E2E/Noise anchor unnoticed (R5), so PoP is a **required
 * field**, not optional.
 *
 * The Phase-1 transport is the masked [ControlPlaneConnector] stub (no live CP yet — that is S-D); this slice lays
 * the anchor (identity + PoP) the CP will admit. [verifyPop] models the CP-side admission check so "PoP mandatory"
 * is a real, mutation-proven gate here, not a promise deferred to code that doesn't exist yet.
 */
@Serializable
data class HubRegistration(
    val hubId: String,
    val ownerId: String,
    val name: String,
    val defaultPort: Int,
    /** base64 raw 32-byte Ed25519 public (identity / PoP verification key). */
    val signingPubKey: String,
    /** base64 raw 32-byte X25519 public — the Noise-ready static (Phase-2 Noise_NK). */
    val dhPubKey: String,
    /** base64 Ed25519 signature over the CP-issued nonce (Proof-of-Possession, R3) — **MANDATORY**. */
    val pop: String,
    /**
     * CYP-804 ① — the hub's per-hub ISSUER-TRUST posture (axis c) self-reported at admission, so the CP can publish
     * it on [com.tneff.cyppieagents.model.HubDescriptor.issuerTrust] for the client to distinguish
     * owned-but-issuer-not-trusted from offline. Connectability metadata (like the CP's presence view), inside the
     * zero-knowledge boundary — NOT payload. Additive LAST field, nullable-default = absent-when-unknown.
     * (Transcript binding of this field is a separate PL-signed-off step — the "every field bound" invariant.)
     */
    val issuerTrust: HubIssuerTrust? = null,
)

class ControlPlaneRegistrar(
    private val identity: HubIdentity,
    private val signer: HubIdentityProvisioner,
    private val connector: ControlPlaneConnector,
    private val ownerId: String,
    private val name: String,
    private val defaultPort: Int,
    /** CYP-804 ① — the hub's issuer-trust posture (axis c) to self-report at admission; null when unknown. */
    private val issuerTrust: HubIssuerTrust? = null,
) {
    /**
     * Build and send a registration with a **mandatory** PoP over the CP-issued [cpNonce]. Fail-closed: an empty
     * nonce means there is no challenge to prove possession against, so we refuse to register an unproven anchor
     * (R3/F3) rather than emit a PoP-less registration a lax CP might accept.
     */
    suspend fun register(cpNonce: ByteArray): HubRegistration {
        require(cpNonce.isNotEmpty()) {
            "hub registration requires a CP-issued nonce — PoP is mandatory (R3/F3); refusing to register unproven"
        }
        // CYP-451 (DESIGN-3 carry-forward): the PoP signs the whole TRANSCRIPT (cpNonce + every registered field,
        // incl. dhPubKey), not just the nonce — so a valid PoP cannot cover a SUBSTITUTED dhPubKey (which would
        // otherwise poison the Phase-2 Noise anchor, R5). Build the fields first, then sign their transcript.
        val fields = HubRegistration(
            hubId = identity.hubId,
            ownerId = ownerId,
            name = name,
            defaultPort = defaultPort,
            signingPubKey = identity.signingPubKey,
            dhPubKey = identity.dhPubKey,
            pop = "", // placeholder — the PoP is over the OTHER fields + nonce, never over itself
            issuerTrust = issuerTrust,
        )
        val pop = Base64.getEncoder().encodeToString(signer.sign(RegistrationTranscript.bytes(fields, cpNonce)))
        val reg = fields.copy(pop = pop)
        // Phase-1 legacy path kept for the S-C stub; S-D's live CP admission is [com.tneff.cyppieagents.controlplane.HubRegistrar].
        connector.egress(JSON.encodeToString(HubRegistration.serializer(), reg))
        return reg
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * The PoP admission check: [HubRegistration.pop] must be a valid Ed25519 signature by the claimed
         * [HubRegistration.signingPubKey] over the **transcript** (`cpNonce` + all registered fields). A blank /
         * forged / wrong-nonce PoP — OR a substituted dhPubKey (any field change alters the transcript) — is
         * rejected. This is the real CP-side gate; the S-D [com.tneff.cyppieagents.controlplane.HubRegistrar]
         * composes it with the `hubId == deriveHubId` check.
         */
        fun verifyPop(reg: HubRegistration, cpNonce: ByteArray): Boolean {
            if (reg.pop.isBlank() || cpNonce.isEmpty()) return false
            val sig = runCatching { Base64.getDecoder().decode(reg.pop) }.getOrNull() ?: return false
            val pub = runCatching { Base64.getDecoder().decode(reg.signingPubKey) }.getOrNull() ?: return false
            return RawKeys.ed25519Verify(pub, RegistrationTranscript.bytes(reg, cpNonce), sig)
        }
    }
}

/**
 * CYP-451 — the canonical, **injective** registration transcript the PoP is computed over. Length-prefixed
 * encoding (4-byte BE length + UTF-8 bytes per component), so no two distinct registrations can collide onto the
 * same bytes (the same non-injectivity trap [com.tneff.cyppieagents.crypto.SecretAad] avoids). The `pop` field is
 * excluded (it signs the rest). Every field is bound — a substituted dhPubKey yields a different transcript, so a
 * PoP made for the real dhPubKey does not verify against the substituted one.
 */
object RegistrationTranscript {
    fun bytes(reg: HubRegistration, cpNonce: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun lp(b: ByteArray) {
            out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            out.write(b)
        }
        lp("cyppie-hub-registration-v1".encodeToByteArray()) // domain-separation tag
        lp(cpNonce)
        lp(reg.hubId.encodeToByteArray())
        lp(reg.ownerId.encodeToByteArray())
        lp(reg.name.encodeToByteArray())
        lp(reg.defaultPort.toString().encodeToByteArray())
        lp(reg.signingPubKey.encodeToByteArray())
        lp(reg.dhPubKey.encodeToByteArray())
        // CYP-804 ① (PL-0108 sign-off) — BIND the issuer-trust posture: preserves the "every field bound" invariant AND
        // gives security VALUE — a MITM that flips NOT_TRUSTED→TRUSTED in transit (to suppress the client warning)
        // changes these bytes → the hub's PoP no longer verifies → caught. null→"" is injective (enum names are non-empty).
        lp((reg.issuerTrust?.name ?: "").encodeToByteArray())
        return out.toByteArray()
    }
}
