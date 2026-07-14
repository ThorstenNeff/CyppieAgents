package com.tneff.cyppieagents.controlplane

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-512 — the admission challenge nonce is **single-use + anti-replay**: an issued nonce consumes exactly once; a
 * replay (or a never-issued / blank nonce) is rejected fail-closed.
 */
class Cyp512HubAdmissionNonceTest {

    @Test fun issuedNonce_consumesOnce_thenReplayRejected() {
        val store = HubAdmissionNonce()
        val n = store.issue()
        assertTrue(store.consume(n), "an issued nonce consumes once")
        assertFalse(store.consume(n), "a replay of the SAME nonce is rejected (single-use, anti-replay)")
    }

    @Test fun neverIssuedNonce_rejected() {
        assertFalse(HubAdmissionNonce().consume(ByteArray(32) { 9 }), "a never-issued nonce is rejected")
    }

    @Test fun blankNonce_rejected() {
        assertFalse(HubAdmissionNonce().consume(ByteArray(0)), "a blank nonce is rejected fail-closed")
    }

    @Test fun distinctIssues_areIndependent() {
        val store = HubAdmissionNonce()
        val a = store.issue(); val b = store.issue()
        assertTrue(store.consume(a)); assertTrue(store.consume(b), "distinct nonces consume independently")
    }

    // ---- CYP-563 — the store is BOUNDED: TTL eviction + a hard cap (was: unbounded issued set) ----

    @Test fun withinTtl_consumesOk_butExpired_rejectedFailClosed() {
        var t = 0L
        val store = HubAdmissionNonce(ttlMs = 1_000, now = { t })
        val fresh = store.issue()
        t = 999
        assertTrue(store.consume(fresh), "a nonce within its TTL still consumes")
        val stale = store.issue()
        t = 999 + 1_001 // advance past the TTL of `stale`
        assertFalse(store.consume(stale), "an EXPIRED (issued-but-stale) nonce is rejected fail-closed (CYP-563)")
    }

    @Test fun expiredNonces_evictedOnIssue_outstandingBounded() {
        var t = 0L
        val store = HubAdmissionNonce(ttlMs = 1_000, now = { t })
        repeat(5) { store.issue() }
        t = 5_000 // all five are now expired
        store.issue() // triggers evictExpired()
        assertEquals(1, store.outstanding(), "expired nonces are evicted on issue → only the fresh one remains (bounded)")
    }

    @Test fun maxOutstanding_hardCap_boundsMemory_evenWithinTtl() {
        val store = HubAdmissionNonce(ttlMs = 10 * 60_000, maxOutstanding = 3, now = { 0L }) // frozen clock: nothing expires
        repeat(10) { store.issue() }
        assertTrue(store.outstanding() <= 3, "the hard cap bounds outstanding nonces even within the TTL window (CYP-563)")
    }
}
