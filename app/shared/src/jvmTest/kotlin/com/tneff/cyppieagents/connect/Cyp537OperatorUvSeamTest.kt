package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.UvReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-537 F⑥-1 (PO ruling (b), Assist-gated) — the operator-UV **test-seam** on the live factory. The prod cache is
 * built by [buildOperatorUvCache] (bounded window + monotonic clock); the factory's `rawUserVerification` param
 * defaults to the prod fail-closed stub (Unavailable ⇒ PROD STAYS INERT) and is overridable ONLY in tests to a
 * verifying UV. This pins BOTH modes through the SAME prod construction: the default-equivalent fails closed (never
 * one silent grant leaks to the next tunnel), while an injected verifying UV yields the "1 UV for N" positive path.
 */
class Cyp537OperatorUvSeamTest {

    private class CountingUv(private val outcome: UvOutcome) : UserVerification {
        var prompts = 0
        override suspend fun verify(reason: UvReason): UvOutcome { prompts++; return outcome }
    }

    @Test
    fun prodDefaultEquivalent_failsClosed_neverCachesAGrant() = runTest {
        // The prod default raw UV is Unavailable (headless / real WebAuthn gates prod). Through the prod cache it must
        // NEVER satisfy a tunnel — each authenticate re-delegates (no silent grant inherited).
        val raw = CountingUv(UvOutcome.Unavailable)
        val cache = buildOperatorUvCache(raw)
        repeat(4) { assertEquals(UvOutcome.Unavailable, cache.verify(UvReason.OPERATOR_AUTH)) }
        assertEquals(4, raw.prompts, "prod-inert: a non-Verified UV is never cached ⇒ every tunnel re-checks (fail-closed)")
    }

    @Test
    fun testOverrideVerifyingUv_oneUvForN_throughProdCache() = runTest {
        // The e2e/test override: a VERIFYING raw UV. Through the SAME prod cache (120s window) N authenticates cost ONE
        // prompt — the "1 UV for N" property the joint 2-tunnel e2e proves over real tunnels.
        val raw = CountingUv(UvOutcome.Verified)
        val cache = buildOperatorUvCache(raw)
        repeat(6) { assertEquals(UvOutcome.Verified, cache.verify(UvReason.OPERATOR_AUTH)) }
        assertEquals(1, raw.prompts, "an injected verifying UV ⇒ ONE prompt for N tunnels (the positive path)")
    }
}
