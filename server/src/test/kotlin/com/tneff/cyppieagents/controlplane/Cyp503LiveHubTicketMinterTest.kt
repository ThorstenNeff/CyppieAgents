package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.crypto.RawKeys
import com.tneff.cyppieagents.transport.RemoteRelayWiring
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-503 — the [LiveHubTicketMinter] activation-gate teeth (P1/P2/P3/P4a). The gate points that touch identity are
 * proven **at the wire** through the merged S-E [CpJwtVerifier] (not a decode we control), so a regression can't
 * hide behind a hand-rolled assertion. P4b (the min()-single-source hub-cap boundary) is `Cyp503TtlSingleSourceTest`.
 *
 * ★ Mutation map (revert the guard → the named tooth reds):
 *  - drop `hub.ownerId != operatorId` → [p1_nonOwner_isDenied] reds (a non-owner gets a cpJwt).
 *  - source `sub` from anywhere but `authenticate` → [p2_sub_isAuthenticatedOperator_verifiedAtTheWire] reds.
 *  - recompute/override `cb` instead of `request.cb` → [p3_cb_isPassedThrough_boundToLiveH] reds (wrong-h stops accepting).
 *  - a literal `ttlMs` ≠ the constant → [p4a_ticketTtl_isSingleSourced] reds.
 */
class Cyp503LiveHubTicketMinterTest {

    private val cp = RawKeys.generateEd25519() // the CP's OWN signing key
    private val minter = CpJwtMinter(cp.privateRaw, kid = "cp1", issuer = "cp")
    private val hubId = "hub_abcdef0123456789"
    private val owner = "operator-1"
    private val pin: (String?) -> ByteArray? = { kid -> if (kid == "cp1") cp.publicRaw else null }

    private fun registrarWith(ownerId: String) = HubRegistrar(
        ConcurrentHashMap(mapOf(hubId to RegisteredHub(hubId, ownerId, "hub-1", 8787, "spub", "dpub"))),
    )

    /** The decoded JWT payload — so a tooth can pin ONE claim in isolation (sub-only / cb-only), attributable to
     *  exactly one gate point, alongside the stronger end-to-end wire proof. */
    private fun payloadOf(token: String) =
        Json.parseToJsonElement(Base64.getUrlDecoder().decode(token.split(".")[1]).decodeToString()).jsonObject

    private fun live(authOperator: String?, registrar: HubRegistrar, now: Long = 1_000_000_000_000L) =
        LiveHubTicketMinter({ authOperator }, registrar, minter, { now })

    // ---- P1 — owner-check, fail-closed ----

    @Test fun p1_nonOwner_isDenied() {
        // authenticated op-A, but the hub is owned by op-B → terminal deny, NO cpJwt.
        val r = live("op-A", registrarWith("op-B")).mint(HubTicketRequest(hubId, "cb"), CpOperatorSession("s"))
        assertNull(r.cpJwt, "a non-owner must not receive a minted hubTicket")
        assertEquals(HubTicketFailure.NOT_AUTHORIZED_FOR_HUB, r.failure)
    }

    @Test fun p1_unknownHub_isDenied() {
        val r = live(owner, HubRegistrar()).mint(HubTicketRequest(hubId, "cb"), CpOperatorSession("s"))
        assertNull(r.cpJwt, "an unknown hub is fail-closed, not minted")
        assertEquals(HubTicketFailure.NOT_AUTHORIZED_FOR_HUB, r.failure)
    }

    @Test fun deadSession_isDenied_distinctTruth() {
        val r = live(null, registrarWith(owner)).mint(HubTicketRequest(hubId, "cb"), CpOperatorSession("dead"))
        assertNull(r.cpJwt)
        assertEquals(HubTicketFailure.CP_SESSION_EXPIRED, r.failure, "a dead session is the non-terminal truth")
    }

    // ---- P2 — sub = the authenticated operator, proven at the wire ----

    @Test fun p2_sub_isAuthenticatedOperator_verifiedAtTheWire() {
        val now = 1_000_000_000_000L
        val h = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val cb = TokenPredicates.expectedChannelBinding(h, hubId)
        val token = assertNotNull(
            live(owner, registrarWith(owner), now).mint(HubTicketRequest(hubId, cb), CpOperatorSession("s")).cpJwt,
        )
        // isolated: the `sub` claim IS the authenticated operator (cb-agnostic — attributable to P2 alone).
        assertEquals(owner, payloadOf(token)["sub"]!!.jsonPrimitive.content, "sub = the authenticated operator")
        // pinned to the AUTHENTICATED operator → a Human principal (the real S-E verifier, not a local decode).
        val principal = CpJwtVerifier().verify(token, VerifierContext(hubId, owner, h, "cp", pin, now))
        assertIs<AuthPrincipal.Human>(principal)
        assertEquals(owner, principal.identityId)
        assertEquals(AuthRole.OPERATOR, principal.role)
        // pinned to a DIFFERENT operator → rejected: the sub is bound to the authenticated id, not requestable.
        assertNull(
            CpJwtVerifier().verify(token, VerifierContext(hubId, "someone-else", h, "cp", pin, now)),
            "a different subject-pin must not verify — sub is the authenticated operator",
        )
    }

    // ---- P3 — cb passed 1:1, bound to the live h at the hub ----

    @Test fun p3_cb_isPassedThrough_boundToLiveH() {
        val now = 1_000_000_000_000L
        val h = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val cb = TokenPredicates.expectedChannelBinding(h, hubId)
        val token = assertNotNull(
            live(owner, registrarWith(owner), now).mint(HubTicketRequest(hubId, cb), CpOperatorSession("s")).cpJwt,
        )
        // isolated: the `cb` claim IS the request cb, verbatim (sub-agnostic — attributable to P3 alone).
        assertEquals(cb, payloadOf(token)["cb"]!!.jsonPrimitive.content, "cb is passed through 1:1, never recomputed")
        // the SAME session h → accepted (the CP-minted cb matches the hub's live-h derivation).
        assertIs<AuthPrincipal.Human>(CpJwtVerifier().verify(token, VerifierContext(hubId, owner, h, "cp", pin, now)))
        // a DIFFERENT session h' → rejected (CHANNEL_BINDING fails) — the exact reason a client-supplied cb is safe.
        val hPrime = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 9)
        assertNull(
            CpJwtVerifier().verify(token, VerifierContext(hubId, owner, hPrime, "cp", pin, now)),
            "a token minted for session h must not verify under a different session h' (anti-cross-session-replay)",
        )
    }

    // ---- P4a — the ticket TTL is the single-sourced Op-Session-TTL ----

    @Test fun p4a_ticketTtl_isSingleSourced() {
        val now = 1_700_000_000_000L
        val token = assertNotNull(
            live(owner, registrarWith(owner), now).mint(HubTicketRequest(hubId, "cb"), CpOperatorSession("s")).cpJwt,
        )
        val payload = Json.parseToJsonElement(Base64.getUrlDecoder().decode(token.split(".")[1]).decodeToString())
        fun claimSec(name: String) = payload.jsonObject[name]!!.jsonPrimitive.long
        assertEquals(
            RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS, (claimSec("exp") - claimSec("nbf")) * 1000L,
            "the hubTicket lifetime is the single-sourced Op-Session-TTL, not an independently-chosen literal",
        )
        assertEquals(now / 1000L, claimSec("nbf"), "nbf = the mint instant")
    }
}
