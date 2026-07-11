package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.crypto.RawKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * CYP-447 (S-E / F1-A) — the offline **CP-JWT** [IdentityToken] impl: a compact JWS (`header.payload.signature`)
 * signed by the Control Plane's own Ed25519 key (R1-A). Verification order, all fail-closed:
 *  1. **structural** parse (exactly 3 base64url parts, valid JSON);
 *  2. **HARD single-alg pin** — the header `alg` MUST be `EdDSA`; `none`/`HS*`/`RS*`/`ES*` are rejected. Pinning to
 *     ONE algorithm is the structural defeat of alg-confusion (we never let the token dictate how it's verified);
 *  3. **Ed25519 signature** over the ASCII `header.payload` with the CP's pinned public key for the token's `kid`
 *     (the pin is a [VerifierContext.cpPublicKey] config/keystore input — an unknown kid → reject), via the reused
 *     S-C [RawKeys.ed25519Verify] (no new crypto dep);
 *  4. the **AND-conjoined** [TokenPredicate] list (`aud==hubId` ∧ `iss==CP` ∧ exp/nbf ∧ `sub==pin` ∧ channel-binding).
 *
 * Only if ALL hold does it return the authenticated operator [AuthPrincipal]; any failure → `null`.
 */
class CpJwtVerifier(
    private val predicates: List<TokenPredicate> = TokenPredicates.PHASE1,
    private val pinnedAlg: String = "EdDSA",
) : IdentityToken {

    override fun verify(token: String, ctx: VerifierContext): AuthPrincipal? {
        val parsed = parse(token) ?: return null                 // structural + HARD alg-pin
        val cpPub = ctx.cpPublicKey(parsed.kid) ?: return null    // no trusted CP root for this kid → reject
        if (!RawKeys.ed25519Verify(cpPub, parsed.signingInput, parsed.signature)) return null
        if (predicates.any { !it.holds(parsed.claims, ctx) }) return null   // a∧b∧c — a single false fails all
        val sub = parsed.claims.sub ?: return null
        return AuthPrincipal.Human(sub, AuthRole.OPERATOR)        // sub==pin already enforced → the pinned operator
    }

    private class Parsed(
        val kid: String?,
        val signingInput: ByteArray,
        val signature: ByteArray,
        val claims: TokenClaims,
    )

    private fun parse(token: String): Parsed? = runCatching {
        val parts = token.split(".")
        if (parts.size != 3) return@runCatching null
        val header = JSON.parseToJsonElement(decodeStr(parts[0])).jsonObject
        if (header["alg"]?.jsonPrimitive?.content != pinnedAlg) return@runCatching null // HARD alg-pin (rejects none/HS/RS/ES)
        val kid = header["kid"]?.jsonPrimitive?.content
        val payload = JSON.parseToJsonElement(decodeStr(parts[1])).jsonObject
        val claims = TokenClaims(
            iss = payload["iss"]?.jsonPrimitive?.content,
            aud = audienceOf(payload),
            sub = payload["sub"]?.jsonPrimitive?.content,
            expSec = payload["exp"]?.jsonPrimitive?.longOrNull,
            nbfSec = payload["nbf"]?.jsonPrimitive?.longOrNull,
            cb = payload["cb"]?.jsonPrimitive?.content,
        )
        Parsed(
            kid = kid,
            signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray(), // ASCII base64url header.payload (RFC 7515)
            signature = decodeBytes(parts[2]),
            claims = claims,
        )
    }.getOrNull()

    /** `aud` may be a single string or an array of strings (RFC 7519); either way → a list. */
    private fun audienceOf(payload: JsonObject): List<String> {
        val aud = payload["aud"] ?: return emptyList()
        return runCatching {
            when (aud) {
                is JsonArray -> aud.map { it.jsonPrimitive.content }
                else -> listOf(aud.jsonPrimitive.content)
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeBytes(b64url: String): ByteArray = Base64.getUrlDecoder().decode(b64url)
    private fun decodeStr(b64url: String): String = String(decodeBytes(b64url), Charsets.UTF_8)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
