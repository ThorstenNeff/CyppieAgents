package com.tneff.cyppieagents.controlplane

import com.tneff.cyppieagents.boot.HubRegistration
import com.tneff.cyppieagents.boot.RegistrationTranscript
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.RawKeys
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CYP-563 — the [HubRegistrar] per-owner hub cap bounds registry growth (was: `registry[hubId] = hub` with no cap).
 * The cap sits AFTER the PoP + self-certifying-hubId gates (so it never widens the auth surface) and BEFORE the insert.
 * A re-admit of an already-registered hubId is an UPDATE (exempt); only a NEW hubId for an owner already at the cap is
 * rejected (`owner_hub_cap`). Per-owner, not global.
 *
 * ★ Mutation: drop the `owner_hub_cap` check → [perOwnerCap_rejectsNewHubBeyondCap] reds (the 3rd hub admits).
 */
class Cyp563HubRegistrarCapTest {

    private val nonce = ByteArray(32) { 3 }
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** A valid, self-certifying registration for a FRESH signing key, owned by [owner] (PoP over the transcript). */
    private fun freshReg(owner: String): HubRegistration {
        val k = RawKeys.generateEd25519()
        val hubId = HubIdentityProvisioner.deriveHubId(k.publicRaw)
        val fields = HubRegistration(hubId, owner, "hub", 8787, b64(k.publicRaw), b64(RawKeys.generateEd25519().publicRaw), pop = "")
        return fields.copy(pop = b64(RawKeys.ed25519Sign(k.privateRaw, RegistrationTranscript.bytes(fields, nonce))))
    }

    @Test fun perOwnerCap_rejectsNewHubBeyondCap_owner_hub_cap() {
        val reg = HubRegistrar(ConcurrentHashMap(), maxHubsPerOwner = 2)
        assertIs<AdmitResult.Admitted>(reg.admit(freshReg("op-1"), nonce), "1st hub admits")
        assertIs<AdmitResult.Admitted>(reg.admit(freshReg("op-1"), nonce), "2nd hub admits (at cap)")
        val third = reg.admit(freshReg("op-1"), nonce)
        assertIs<AdmitResult.Rejected>(third, "a 3rd NEW hub for the same owner is capped")
        assertEquals("owner_hub_cap", (third as AdmitResult.Rejected).reason)
    }

    @Test fun perOwnerCap_isPerOwner_notGlobal() {
        val reg = HubRegistrar(ConcurrentHashMap(), maxHubsPerOwner = 1)
        assertIs<AdmitResult.Admitted>(reg.admit(freshReg("op-1"), nonce), "op-1's one hub admits")
        assertIs<AdmitResult.Admitted>(reg.admit(freshReg("op-2"), nonce), "a DIFFERENT owner is not blocked by op-1's cap")
    }

    @Test fun reAdmitSameHubId_isUpdate_exemptFromCap() {
        val reg = HubRegistrar(ConcurrentHashMap(), maxHubsPerOwner = 1)
        val r = freshReg("op-1")
        assertIs<AdmitResult.Admitted>(reg.admit(r, nonce), "1st admit (owner now at cap)")
        assertIs<AdmitResult.Admitted>(reg.admit(r, nonce), "a re-admit of the SAME hubId is an update, not capped")
    }
}
