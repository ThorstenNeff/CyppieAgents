package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.boot.ControlPlaneRegistrar
import com.tneff.cyppieagents.boot.HubRegistration
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-451 (S-D) — a hub as the **Control Plane** admitted it: identity + routing only, **never any payload**
 * (the zero-knowledge boundary — the CP sees who/where, not what).
 */
data class RegisteredHub(
    val hubId: String,
    val ownerId: String,
    val name: String,
    val defaultPort: Int,
    val signingPubKey: String,
    val dhPubKey: String,
)

sealed interface AdmitResult {
    data class Admitted(val hub: RegisteredHub) : AdmitResult
    /** [reason] is a stable code (never secret) for the CP's own logs. */
    data class Rejected(val reason: String) : AdmitResult
}

/**
 * CYP-451 (S-D) — the **production** hub registrar that replaces the S-C stub's modelled admission. It is the
 * server-side Control-Plane component, **built INERT** (no live service until an explicit Phase-2 remote-GO). It
 * enforces BOTH Reviewer carry-forward AC, fail-closed, before a hub enters the registry:
 *  1. **Transcript PoP** ([ControlPlaneRegistrar.verifyPop]) — the PoP is a valid Ed25519 signature by the claimed
 *     signing key over the WHOLE registration transcript (incl. `dhPubKey`), so `dhPubKey` cannot be substituted
 *     under a valid PoP (R5).
 *  2. **Self-certifying hubId** — `hubId == deriveHubId(signingPubRaw)` is VERIFIED (not merely stored), so the CP
 *     cannot be tricked into keying a registry entry to a hubId that does not derive from its own signing key.
 *
 * The registry keys by the self-certifying hubId. Zero-knowledge: it stores only identity/routing metadata.
 */
class HubRegistrar(
    private val registry: ConcurrentHashMap<String, RegisteredHub> = ConcurrentHashMap(),
) {
    fun admit(reg: HubRegistration, cpNonce: ByteArray): AdmitResult {
        // (1) Transcript PoP — fail-closed. A substituted dhPubKey (or any field) alters the transcript → invalid.
        if (!ControlPlaneRegistrar.verifyPop(reg, cpNonce)) return AdmitResult.Rejected("pop_invalid")
        // (2) hubId MUST be the self-certifying derivation of the signing public key (verify, not just store).
        val signingPubRaw = runCatching { Base64.getDecoder().decode(reg.signingPubKey) }.getOrNull()
            ?: return AdmitResult.Rejected("signing_pub_malformed")
        if (reg.hubId != HubIdentityProvisioner.deriveHubId(signingPubRaw)) {
            return AdmitResult.Rejected("hubid_not_self_certifying")
        }
        val hub = RegisteredHub(reg.hubId, reg.ownerId, reg.name, reg.defaultPort, reg.signingPubKey, reg.dhPubKey)
        registry[hub.hubId] = hub
        return AdmitResult.Admitted(hub)
    }

    fun lookup(hubId: String): RegisteredHub? = registry[hubId]

    fun count(): Int = registry.size
}
