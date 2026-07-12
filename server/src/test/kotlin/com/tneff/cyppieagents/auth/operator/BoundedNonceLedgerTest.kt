package com.tneff.cyppieagents.auth.operator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-476 ① — the bounded/TTL nonce ledger teeth: single-use, TTL expiry, and the **hard size bound** (the prod
 * memory-leak fix). Durability isn't tested because it's deliberately not a requirement (the PoP's `h` channel-binding
 * covers cross-restart replay; this ledger is in-session defence-in-depth).
 */
class BoundedNonceLedgerTest {

    private fun n(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun singleUse_firstTrue_replayFalse() {
        val l = BoundedNonceLedger(now = { 0L })
        assertTrue(l.useOnce(n(1, 2, 3)), "first use is fresh")
        assertFalse(l.useOnce(n(1, 2, 3)), "a replay within TTL is rejected")
    }

    @Test fun ttl_replayWithin_freshAfter() {
        var t = 0L
        val l = BoundedNonceLedger(ttlMs = 1000, now = { t })
        assertTrue(l.useOnce(n(1)))
        t = 999
        assertFalse(l.useOnce(n(1)), "still within TTL → replay")
        t = 1001
        assertTrue(l.useOnce(n(1)), "past TTL → fresh again (the seen-window expired)")
    }

    /** ★ The memory-leak fix: the ledger is HARD-capped — past [maxEntries] the oldest is evicted (no unbounded growth). */
    @Test fun bounded_evictsOldest_pastMax() {
        val l = BoundedNonceLedger(maxEntries = 2, ttlMs = Long.MAX_VALUE, now = { 0L })
        assertTrue(l.useOnce(n(1)))
        assertTrue(l.useOnce(n(2)))
        assertTrue(l.useOnce(n(3))) // size 3 > max 2 → evicts the eldest (n1)
        assertTrue(l.useOnce(n(1)), "n1 was evicted (bounded) → treated as fresh, not a stale replay")
        assertFalse(l.useOnce(n(3)), "n3 is still within the cap → replay")
    }
}
