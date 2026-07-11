package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.crypto.RawKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * CYP-447 (S-E) teeth — the CP-JWT [IdentityToken] verifier. Hermetic (a test CP Ed25519 key mints tokens; the
 * verifier uses the reused S-C [RawKeys], already runtime-verified). Every reject path is a NON-vacuous tooth:
 * removing the matching predicate/pin lets its "wrong-X" token through → red.
 *
 * ★ [foreignSessionHandshake_rejected] — the Noise channel-binding is real: a token minted for a different `h` is
 *   rejected (anti-replay). ★ [algConfusion_rejected] — a VALIDLY Ed25519-signed token whose header lies `alg:none`
 *   / `HS256` is rejected by the hard pin (removing the pin lets it through, since the signature really is valid).
 *   ★ [wrongAudience_rejected] / [wrongSubject_rejected] — F6: another hub's / another user's token can't open this.
 */
class Cyp447IdentityVerifierTest {

    private val hubId = "hub_abcdef0123456789"
    private val operatorId = "operator-pinned-1"
    private val issuer = "cp"
    private val kid = "cp1"
    private val nowMs = 1_000_000_000_000L
    private val liveH = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    private val cp = RawKeys.generateEd25519() // the CP signing key (its pub is the pinned root)
    private val verifier = CpJwtVerifier()

    private fun ctx(handshake: ByteArray = liveH, pub: ByteArray? = cp.publicRaw) = VerifierContext(
        hubId = hubId,
        pinnedOperatorId = operatorId,
        handshakeHash = handshake,
        expectedIssuer = issuer,
        cpPublicKey = { k -> if (k == kid) pub else null },
        nowMs = nowMs,
    )

    private fun cbFor(h: ByteArray) = TokenPredicates.expectedChannelBinding(h, hubId)

    /** Mint a CP-JWT; every field overridable so a single claim can be made wrong. `signSeed`/`algHeader` let a
     *  token be VALIDLY signed yet carry a lying `alg` (the alg-confusion tooth). */
    private fun mint(
        alg: String = "EdDSA",
        kidHeader: String? = kid,
        iss: String = issuer,
        aud: String = hubId,
        sub: String = operatorId,
        expSec: Long = nowMs / 1000 + 300,
        nbfSec: Long? = null,
        cb: String = cbFor(liveH),
        signSeed: ByteArray = cp.privateRaw,
        tamperSig: Boolean = false,
    ): String {
        val header = buildJsonObject {
            put("alg", alg); put("typ", "JWT"); if (kidHeader != null) put("kid", kidHeader)
        }
        val payload = buildJsonObject {
            put("iss", iss); put("aud", aud); put("sub", sub); put("exp", expSec)
            if (nbfSec != null) put("nbf", nbfSec); put("cb", cb)
        }
        val h = b64(header); val p = b64(payload)
        val sig = RawKeys.ed25519Sign(signSeed, "$h.$p".encodeToByteArray())
        if (tamperSig) sig[sig.size / 2] = (sig[sig.size / 2].toInt() xor 0x40).toByte()
        return "$h.$p.${Base64.getUrlEncoder().withoutPadding().encodeToString(sig)}"
    }

    private fun b64(o: JsonObject) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(JsonObject.serializer(), o).encodeToByteArray())

    @Test fun validToken_authenticatesPinnedOperator() {
        val p = verifier.verify(mint(), ctx())
        assertIs<AuthPrincipal.Human>(p)
        assertEquals(operatorId, p.identityId)
        assertEquals(AuthRole.OPERATOR, p.role)
    }

    @Test fun wrongAudience_rejected() = assertNull(verifier.verify(mint(aud = "hub_deadbeefdeadbeef"), ctx()))

    @Test fun wrongSubject_rejected() = assertNull(verifier.verify(mint(sub = "someone-else"), ctx()))

    @Test fun wrongIssuer_rejected() = assertNull(verifier.verify(mint(iss = "evil-cp"), ctx()))

    @Test fun expired_rejected() = assertNull(verifier.verify(mint(expSec = nowMs / 1000 - 3600), ctx()))

    @Test fun notYetValid_rejected() = assertNull(verifier.verify(mint(nbfSec = nowMs / 1000 + 3600), ctx()))

    @Test fun foreignSessionHandshake_rejected() {
        // token minted binding to liveH, but verified against a DIFFERENT session's h → cb mismatch → reject.
        val foreign = byteArrayOf(9, 9, 9, 9)
        assertNull(verifier.verify(mint(cb = cbFor(liveH)), ctx(handshake = foreign)))
        // and a token bound to the foreign session is rejected on the live one (symmetric).
        assertNull(verifier.verify(mint(cb = cbFor(foreign)), ctx(handshake = liveH)))
    }

    @Test fun algConfusion_rejected() {
        // VALIDLY Ed25519-signed, but the header lies about alg → the hard pin rejects (remove the pin → passes).
        assertNull(verifier.verify(mint(alg = "none"), ctx()))
        assertNull(verifier.verify(mint(alg = "HS256"), ctx()))
        assertNull(verifier.verify(mint(alg = "RS256"), ctx()))
        assertNull(verifier.verify(mint(alg = "ES256"), ctx()))
    }

    @Test fun tamperedSignature_rejected() = assertNull(verifier.verify(mint(tamperSig = true), ctx()))

    @Test fun unknownKid_rejected() {
        // the CP root pin has no key for this kid → reject (never trust an unpinned signer).
        assertNull(verifier.verify(mint(kidHeader = "unknown-kid"), ctx()))
    }

    @Test fun wrongCpKey_rejected() {
        // signed by an attacker key, not the pinned CP root → signature fails.
        val attacker = RawKeys.generateEd25519()
        assertNull(verifier.verify(mint(signSeed = attacker.privateRaw), ctx()))
    }

    @Test fun malformed_rejected() {
        assertNull(verifier.verify("not.a.jwt.at.all", ctx()))
        assertNull(verifier.verify("only-one-part", ctx()))
        assertNull(verifier.verify("$$$.%%%.&&&", ctx()))
    }
}
