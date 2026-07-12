package com.tneff.cyppieagents.auth

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-514 — `expectedChannelBinding` now derives `cb` from the shared `:core` [com.tneff.cyppieagents.operator.channelBindingInput]
 * (Option 2: input bytes in `:core`, SHA-256 + base64url-no-pad here). Two teeth:
 *  ★ [refactor_isByteIdentical_toPriorInlineDerivation] — the refactor did not change ANY output vs the prior inline
 *    `SHA-256(h ‖ hubId)` derivation (the byte-identical guarantee the PO required).
 *  ★ [goldenVector_hubVerifierSide] — a known `(h, hubId) → cb` STRING. **Dev checks the SAME vector at the client
 *    `CpJwtProvider` (CYP-496)** — the cross-side lock on the residual (order + base64url-no-pad); if either side
 *    drifts, its golden tooth reds.
 */
class Cyp514ChannelBindingTest {

    @Test
    fun refactor_isByteIdentical_toPriorInlineDerivation() {
        val h = ByteArray(32) { (it * 7 + 3).toByte() }
        val hubId = "hub_abcdef0123456789"
        // the PRIOR inline derivation, recomputed independently.
        val prior = run {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(h)
            md.update(hubId.encodeToByteArray())
            Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest())
        }
        assertEquals(prior, TokenPredicates.expectedChannelBinding(h, hubId), "the :core-helper refactor is byte-identical to the prior derivation")
    }

    @Test
    fun goldenVector_hubVerifierSide() {
        // ★ SHARED golden vector — Dev's client CpJwtProvider (CYP-496) MUST produce the SAME cb for this (h, hubId).
        val h = ByteArray(32) { it.toByte() } // 0x00..0x1F
        val hubId = "cyppie-golden-hub"
        assertEquals(
            "JVDXE7HhN-U3H8Rg61ShpLmVkJ9FQ4KpS5GLRWvnqFA",
            TokenPredicates.expectedChannelBinding(h, hubId),
            "the golden (h, hubId) → cb vector — cross-checked byte-for-byte at both the hub verifier and the client",
        )
    }
}
