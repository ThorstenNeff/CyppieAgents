package com.tneff.cyppieagents.controlplane

import kotlin.test.Test
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
}
