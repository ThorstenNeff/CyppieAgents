package com.tneff.cyppieagents.auth

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-286 — the [WsTicketStore] security contract: mint→consume round-trip, ATOMIC single-use, TTL fail-closed,
 * crypto-random tickets, and TTL-eviction (no unbounded growth). Each guard is pinned; the noted mutation reds it.
 */
class WsTicketStoreTest {

    private var clock = 1_000L
    @BeforeTest fun resetClock() { clock = 1_000L } // the clock is a mutable field — pin it per test (no leak between methods)
    private fun store(ttl: Long = 30_000L) = WsTicketStore(now = { clock }, ttlMs = ttl)

    @Test
    fun mint_thenConsume_returnsTheSubject() {
        val s = store()
        assertEquals("participant:byo-1", s.consume(s.mint("participant:byo-1")))
    }

    @Test
    fun singleUse_secondConsumeIsNull() {
        val s = store()
        val raw = s.mint("operator")
        assertEquals("operator", s.consume(raw), "first consume succeeds")
        assertNull(s.consume(raw), "single-use: a second consume must be null (mutation: byHash[h] read instead of remove → reds)")
    }

    @Test
    fun validJustBeforeExpiry() {
        clock = 1_000
        val s = store(ttl = 100); val r = s.mint("sub"); clock = 1_099 // < 1100
        assertEquals("sub", s.consume(r), "valid just before expiry (1099 < 1100)")
    }

    @Test
    fun failClosedAtExpiry() {
        clock = 1_000
        val s = store(ttl = 100); val r = s.mint("sub"); clock = 1_100 // == expiry
        assertNull(s.consume(r), "at/after expiry → fail-closed (mutation: drop the `now() < expiresAtMs` check → reds)")
    }

    @Test
    fun unknownOrBlank_failClosed() {
        val s = store(); s.mint("x")
        assertNull(s.consume("not-a-real-ticket"))
        assertNull(s.consume(null))
        assertNull(s.consume(""))
    }

    @Test
    fun tickets_areCryptoRandom_andUnique() {
        val s = store()
        val a = s.mint("sub"); val b = s.mint("sub")
        assertNotEquals(a, b, "two mints must differ — crypto-random, not a counter")
        assertTrue(a.length >= 40, "a 256-bit base64url ticket is ~43 chars — sufficient entropy, not guessable")
    }

    @Test
    fun ttlEviction_boundsGrowth_onMint() {
        val s = store(ttl = 100)
        s.mint("a"); s.mint("b"); s.mint("c")
        assertEquals(3, s.size())
        clock = 1_200 // past their expiry (1100)
        s.mint("fresh") // triggers evictExpired
        assertEquals(1, s.size(), "TTL-eviction on mint must drop the 3 lapsed unconsumed tickets (mutation: drop evictExpired → size 4, reds)")
    }
}
