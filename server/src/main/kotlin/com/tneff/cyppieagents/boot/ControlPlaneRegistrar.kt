package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.RawKeys
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
)

class ControlPlaneRegistrar(
    private val identity: HubIdentity,
    private val signer: HubIdentityProvisioner,
    private val connector: ControlPlaneConnector,
    private val ownerId: String,
    private val name: String,
    private val defaultPort: Int,
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
        val pop = Base64.getEncoder().encodeToString(signer.sign(cpNonce))
        val reg = HubRegistration(
            hubId = identity.hubId,
            ownerId = ownerId,
            name = name,
            defaultPort = defaultPort,
            signingPubKey = identity.signingPubKey,
            dhPubKey = identity.dhPubKey,
            pop = pop,
        )
        // Phase-1: the single masked egress (CYP-410 F4) — no live CP yet (S-D). The anchor is what we're laying.
        connector.egress(JSON.encodeToString(HubRegistration.serializer(), reg))
        return reg
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * The CP-side admission check (modeled for S-C so PoP is a real gate): a registration is admitted ONLY if
         * [HubRegistration.pop] is a valid Ed25519 signature by the claimed [HubRegistration.signingPubKey] over
         * the exact nonce the CP issued. A blank/forged/wrong-nonce PoP → rejected. This is the check the Reviewer
         * strand hardens + wires into the real CP (S-D); here it makes the mandatory-PoP tooth non-vacuous.
         */
        fun verifyPop(reg: HubRegistration, cpNonce: ByteArray): Boolean {
            if (reg.pop.isBlank() || cpNonce.isEmpty()) return false
            val sig = runCatching { Base64.getDecoder().decode(reg.pop) }.getOrNull() ?: return false
            val pub = runCatching { Base64.getDecoder().decode(reg.signingPubKey) }.getOrNull() ?: return false
            return RawKeys.ed25519Verify(pub, cpNonce, sig)
        }
    }
}
