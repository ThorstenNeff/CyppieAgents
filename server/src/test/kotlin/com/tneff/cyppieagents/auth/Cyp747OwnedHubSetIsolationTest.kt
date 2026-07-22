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
 * CYP-747 S2 — the OWNED-HUB-SET isolation property (design §2/§5 Step 2 "generalize the verifier anchor" + §11
 * Zahn 2 / N2). Model-2 = ONE operator identity across the SET of hubs they own; `pinnedOperatorId` is that single
 * issuer-asserted identity, the SAME on each owned hub. The set is realized as a PER-HUB CONJUNCTION (sub-pin ∧
 * aud==thisHub ∧ cb ∧ tunnel Device-PoP), NEVER a hub-side owned-set wildcard — so a credential minted for owned
 * Hub-A must NOT laterally open owned Hub-B, while the SAME operator legitimately opens each hub with THAT hub's own
 * aud/cb. Each isolation leg is per-predicate mutation-provable against `TokenPredicates.PHASE1` (drop AUDIENCE →
 * the aud-leg reds; drop CHANNEL_BINDING → the cb-leg reds; drop SUBJECT_PIN → the F6 leg reds). `:server`-only, no
 * crypto/logic change — S2 generalizes the anchor's MEANING, the check (`sub == pinnedOperatorId`) is unchanged.
 * (The third per-hub factor, the tunnel Device-PoP, gates at `Rr3TunnelGate`, outside this verifier's scope.)
 */
class Cyp747OwnedHubSetIsolationTest {

    private val operator = "op-cross-hub-1"          // ONE operator identity, spans owned hubs A and B
    private val hubA = "hub_aaaaaaaaaaaaaaaa"
    private val hubB = "hub_bbbbbbbbbbbbbbbb"
    private val issuer = "relay"
    private val kid = "r1"
    private val nowMs = 1_000_000_000_000L
    private val hSessionA = byteArrayOf(0xA, 0xA, 0xA, 0xA)   // Hub-A's live Noise handshake
    private val hSessionB = byteArrayOf(0xB, 0xB, 0xB, 0xB)   // Hub-B's live Noise handshake

    private val signer = RawKeys.generateEd25519()           // the issuer (relay) signing key
    private val verifier = CpJwtVerifier()

    /** A verifier context AT a given hub with THAT hub's live session — every owned hub pins the SAME operator. */
    private fun ctxAt(hubId: String, h: ByteArray) = VerifierContext(
        hubId = hubId,
        pinnedOperatorId = operator,
        handshakeHash = h,
        expectedIssuer = issuer,
        cpPublicKey = { k -> if (k == kid) signer.publicRaw else null },
        nowMs = nowMs,
    )

    private fun cb(h: ByteArray, hubId: String) = TokenPredicates.expectedChannelBinding(h, hubId)

    private fun mint(sub: String = operator, aud: String, cb: String): String {
        val header = buildJsonObject { put("alg", "EdDSA"); put("typ", "JWT"); put("kid", kid) }
        val payload = buildJsonObject {
            put("iss", issuer); put("aud", aud); put("sub", sub); put("exp", nowMs / 1000 + 300); put("cb", cb)
        }
        val h = b64(header); val p = b64(payload)
        val sig = RawKeys.ed25519Sign(signer.privateRaw, "$h.$p".encodeToByteArray())
        return "$h.$p.${Base64.getUrlEncoder().withoutPadding().encodeToString(sig)}"
    }

    private fun b64(o: JsonObject) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(JsonObject.serializer(), o).encodeToByteArray())

    @Test fun sameOperator_opensEachOwnedHub_positiveControl() {
        // Model-2: the ONE operator identity opens BOTH owned hubs, each with THAT hub's own aud+cb. If S2's
        // generalization broke acceptance, these red.
        val atA = verifier.verify(mint(aud = hubA, cb = cb(hSessionA, hubA)), ctxAt(hubA, hSessionA))
        assertIs<AuthPrincipal.Human>(atA); assertEquals(operator, atA.identityId); assertEquals(AuthRole.OPERATOR, atA.role)
        val atB = verifier.verify(mint(aud = hubB, cb = cb(hSessionB, hubB)), ctxAt(hubB, hSessionB))
        assertIs<AuthPrincipal.Human>(atB); assertEquals(operator, atB.identityId)
    }

    @Test fun ownedHubA_credential_doesNotOpenOwnedHubB_realisticReplay() {
        // A GENUINE, issuer-signed Hub-A credential (aud=A, cb bound to A's session) replayed at owned Hub-B →
        // rejected. Both per-hub factors are wrong for B (aud≠B ∧ cb≠SHA256(h_B‖B)) — no lateral movement across the
        // operator's OWN hub-set even for the legitimate operator (defense-in-depth: either factor alone suffices).
        assertNull(verifier.verify(mint(aud = hubA, cb = cb(hSessionA, hubA)), ctxAt(hubB, hSessionB)))
    }

    @Test fun ownedHubSetIsolation_audGate_independentlyBlocks() {
        // ISOLATE the aud factor: cb is (attacker-)computed CORRECTLY for the live Hub-B session, so ONLY AUDIENCE can
        // reject → proves aud alone gates the owned-set lateral move. Mutation: drop AUDIENCE from PHASE1 → passes → red.
        assertNull(verifier.verify(mint(aud = hubA, cb = cb(hSessionB, hubB)), ctxAt(hubB, hSessionB)))
    }

    @Test fun ownedHubSetIsolation_cbGate_independentlyBlocks() {
        // ISOLATE the cb factor: aud is (attacker-)relabeled CORRECTLY to Hub-B, so ONLY CHANNEL_BINDING can reject
        // (the cred is bound to Hub-A's session h) → proves cb alone gates. Mutation: drop CHANNEL_BINDING → passes → red.
        assertNull(verifier.verify(mint(aud = hubB, cb = cb(hSessionA, hubA)), ctxAt(hubB, hSessionB)))
    }

    @Test fun differentOperator_cannotOpenOwnedHub_f6Preserved() {
        // Model-2 does NOT loosen F6: a valid issuer-signed token for a DIFFERENT operator (correct aud+cb for Hub-B)
        // is rejected by SUBJECT_PIN — the pin is EXACT equality to the owner, never "any issuer-asserted operator".
        // Mutation: drop SUBJECT_PIN → passes → red.
        assertNull(verifier.verify(mint(sub = "other-operator", aud = hubB, cb = cb(hSessionB, hubB)), ctxAt(hubB, hSessionB)))
    }
}
