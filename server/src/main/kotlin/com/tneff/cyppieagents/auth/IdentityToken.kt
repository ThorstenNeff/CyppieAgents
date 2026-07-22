package com.tneff.cyppieagents.auth

import java.security.MessageDigest
import java.util.Base64

/**
 * CYP-447 (S-E / F1-A, F6) — the **`IdentityToken` verifier seam**. It verifies a Control-Plane-signed token and,
 * fail-closed, returns the local [AuthPrincipal] it authenticates — or `null` (never a partial/ambiguous identity),
 * exactly like [IdentityProvider]. This is the offline (F1-A) counterpart to the online Kratos path (F1-B); F1 is
 * a ratification, so this stays a variant-agnostic naht — the impl behind it ([CpJwtVerifier]) is one option, not
 * hardwired.
 *
 * **The authority decision is LOCAL** (`sub == pinnedOperatorId`): a valid issuer-signed token of a DIFFERENT
 * operator must NOT open THIS hub (F6). The verifier never asks the issuer at verify time.
 *
 * CYP-747 S2 (Model-2 — the pin generalizes, §2/§5 Step 2) — `pinnedOperatorId` is the **issuer-asserted operator
 * identity**, ONE identity across the operator's OWNED HUB-SET (each owned hub pins the SAME cross-hub operator id).
 * The set is realized as a per-hub CONJUNCTION (this pin ∧ `aud==thisHub` ∧ `cb` ∧ the tunnel Device-PoP), NEVER a
 * hub-side "owned-set membership" wildcard: a credential minted for owned Hub-A does NOT open owned Hub-B (aud+cb
 * isolate it), while the SAME operator opens each owned hub with that hub's own `aud`/`cb`. The generalization is of
 * the anchor's MEANING, not its check — the per-hub `AND(JWS, Device-PoP)` (§3) is untouched.
 */
interface IdentityToken {
    /** Verify [token] against the live [ctx]; the authenticated principal on success, else `null` (fail-closed). */
    fun verify(token: String, ctx: VerifierContext): AuthPrincipal?
}

/**
 * Everything a verification needs, as explicit INPUTS (no hidden global state) — so the Phase-2 Noise transport
 * (S-D) feeds the live handshake hash `h` in, and the CP signature root is a pinned config/keystore value, never
 * hardcoded.
 */
class VerifierContext(
    /** S-C [com.tneff.cyppieagents.crypto.HubIdentity.hubId] — the token's `aud` MUST equal this. */
    val hubId: String,
    /** The issuer-asserted operator identity — the token's `sub` MUST equal this (F6 authority decision). CYP-747 S2
     *  (Model-2): the operator's ONE cross-hub identity, the SAME on every hub they own; a single EXACT id (equality,
     *  never a set/wildcard), the owned-hub-set staying per-hub-isolated via `aud`+`cb`. */
    val pinnedOperatorId: String,
    /** The live Noise `getHandshakeHash()` — the `cb` claim binds the token to THIS session (anti-replay). */
    val handshakeHash: ByteArray,
    /** The expected token issuer (the CP). */
    val expectedIssuer: String,
    /** The CP signature-root pin: resolves a `kid` → the CP's raw 32-byte Ed25519 public key. A config/keystore
     *  value (S-D contract), NOT hardcoded; an unknown/absent kid → `null` → the verifier rejects. */
    val cpPublicKey: (kid: String?) -> ByteArray?,
    val nowMs: Long,
    /** Clock-skew tolerance for exp/nbf (default 60s). */
    val leewayMs: Long = 60_000L,
)

/** Decoded, shape-validated claims (strings + epoch-SECONDS per the JWT NumericDate convention). */
class TokenClaims(
    val iss: String?,
    val aud: List<String>,
    val sub: String?,
    val expSec: Long?,
    val nbfSec: Long?,
    /** Channel-binding claim: `base64url(SHA-256(h ‖ hubId))`. */
    val cb: String?,
)

/**
 * One AND-conjoined claim check (the crypto gate — alg-pin + signature — runs first in [CpJwtVerifier]). The list
 * is **extensible**: the Phase-2 WebAuthn-PoP branch (CYP-427) appends one more predicate additively, never
 * replacing these. a∧b∧c — never OR: a single false predicate fails the whole verification.
 */
fun interface TokenPredicate {
    fun holds(claims: TokenClaims, ctx: VerifierContext): Boolean
}

/** The Phase-1 predicate set + the channel-binding derivation. */
object TokenPredicates {
    val ISSUER = TokenPredicate { c, ctx -> c.iss != null && c.iss == ctx.expectedIssuer }

    /** F6: the token's audience MUST name this hub — a token for another hub does not open this one. */
    val AUDIENCE = TokenPredicate { c, ctx -> ctx.hubId in c.aud }

    val NOT_EXPIRED = TokenPredicate { c, ctx -> c.expSec != null && ctx.nowMs <= c.expSec * 1000L + ctx.leewayMs }

    val NOT_BEFORE = TokenPredicate { c, ctx -> c.nbfSec == null || ctx.nowMs >= c.nbfSec * 1000L - ctx.leewayMs }

    /** F6 authority: the token's subject MUST be the pinned operator (decided here, never at the issuer). CYP-747 S2
     *  (Model-2): `pinnedOperatorId` is the issuer-asserted operator identity — ONE across the operator's owned
     *  hub-set. EXACT equality, never set-membership: the owned-hub-set is the per-hub conjunction (this ∧ AUDIENCE ∧
     *  CHANNEL_BINDING ∧ tunnel Device-PoP), so an owned-Hub-A credential cannot laterally open owned-Hub-B. */
    val SUBJECT_PIN = TokenPredicate { c, ctx -> c.sub != null && c.sub == ctx.pinnedOperatorId }

    /** Noise channel-binding: the `cb` claim MUST equal `base64url(SHA-256(h ‖ hubId))` recomputed from the LIVE
     *  session — a token minted for a different Noise session (different `h`) fails here (anti-replay). */
    val CHANNEL_BINDING = TokenPredicate { c, ctx -> c.cb != null && c.cb == expectedChannelBinding(ctx.handshakeHash, ctx.hubId) }

    /** a∧b∧c: issuer ∧ audience==hubId ∧ exp/nbf ∧ subject==pin ∧ channel-binding. Order is irrelevant (all AND). */
    val PHASE1: List<TokenPredicate> = listOf(ISSUER, AUDIENCE, NOT_EXPIRED, NOT_BEFORE, SUBJECT_PIN, CHANNEL_BINDING)

    /** `base64url(SHA-256(h ‖ hubId))` — forward-compatible with the Phase-2 WebAuthn `challenge==H(h‖hubId‖…)`. */
    fun expectedChannelBinding(handshakeHash: ByteArray, hubId: String): String {
        // CYP-514: the INPUT bytes (`h ‖ hubId`, h-first, raw) are single-sourced in :core (channelBindingInput) so
        // the client (CpJwtProvider) derives cb from the SAME definition; the SHA-256 + base64url-no-pad stay here on
        // the platform primitive. Byte-identical to the prior inline derivation (Cyp514ChannelBindingTest proves it).
        val md = MessageDigest.getInstance("SHA-256")
        md.update(com.tneff.cyppieagents.operator.channelBindingInput(handshakeHash, hubId))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
    }
}
