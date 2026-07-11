package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.crypto.RawKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * CYP-451 (S-D / F1-A, R1-A) — the Control Plane's **CpJwt minter**: it signs the operator-identity token that the
 * hub's S-E [com.tneff.cyppieagents.auth.CpJwtVerifier] verifies. **EdDSA/Ed25519 only** (the CP's OWN signing key,
 * separate from any hub key), reusing S-C [RawKeys.ed25519Sign] — no new crypto dep. The header ALWAYS pins
 * `alg:EdDSA` (never a downgradeable/negotiable alg), the mirror of the verifier's hard alg-pin.
 *
 * Claims mirror exactly what S-E requires: `iss` (the CP), `aud == hubId`, `sub` (the operator), `nbf`/`exp`, and
 * `cb` (the Noise channel-binding `base64url(SHA-256(h ‖ hubId))`, minted from the live session's `h`). INERT until
 * a Phase-2 remote-GO — nothing calls this in the live local path.
 */
class CpJwtMinter(
    private val cpSigningSeed: ByteArray,
    private val kid: String,
    private val issuer: String,
) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun mint(hubId: String, operatorId: String, channelBinding: String, issuedAtMs: Long, ttlMs: Long): String {
        val header = buildJsonObject {
            put("alg", ALG) // HARD pin at mint — parity with the S-E verifier's alg-pin
            put("kid", kid)
            put("typ", "JWT")
        }
        val payload = buildJsonObject {
            put("iss", issuer)
            put("aud", hubId)
            put("sub", operatorId)
            put("nbf", issuedAtMs / 1000)
            put("exp", (issuedAtMs + ttlMs) / 1000)
            put("cb", channelBinding)
        }
        val signingInput = "${enc(header)}.${enc(payload)}"
        val sig = RawKeys.ed25519Sign(cpSigningSeed, signingInput.encodeToByteArray())
        return "$signingInput.${b64.encodeToString(sig)}"
    }

    private fun enc(o: JsonObject): String =
        b64.encodeToString(Json.encodeToString(JsonObject.serializer(), o).encodeToByteArray())

    private companion object {
        const val ALG = "EdDSA"
    }
}
