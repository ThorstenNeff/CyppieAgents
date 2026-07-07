package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-234b-1 — the [ParticipantTokenStore] contract: mint→resolve round-trip, fail-closed on unknown/expired/
 * revoked, read-tier-only (never operator), and hashed-at-rest (the raw token is never stored). Access-critical
 * → every guard is pinned; the noted mutation reds it.
 */
class ParticipantTokenStoreTest {

    private var clock = 1_000L
    private fun store() = ParticipantTokenStore(now = { clock })

    @Test
    fun mint_thenResolve_returnsTheSubject_readTier() {
        val s = store()
        val raw = s.mint("byo-consumer-1")
        val rec = s.resolve(raw)
        assertEquals("byo-consumer-1", rec?.subject)
        assertEquals(ParticipantTier.READ, rec?.tier, "a participant token is READ-tier by construction (never operator)")
        assertEquals("byo-consumer-1", s.subjectFor(raw))
    }

    @Test
    fun unknownOrBlankToken_resolvesNull_failClosed() {
        val s = store()
        s.mint("someone")
        assertNull(s.resolve("not-a-real-token"), "an unknown token must fail closed")
        assertNull(s.resolve(null))
        assertNull(s.resolve(""))
    }

    @Test
    fun expiredToken_resolvesNull() {
        val s = store()
        val raw = s.mint("temp", ttlMs = 100) // expires at clock(1000)+100 = 1100
        clock = 1_099
        assertEquals("temp", s.resolve(raw)?.subject, "still valid just before expiry")
        clock = 1_100 // at expiry → fail-closed (>= )
        assertNull(s.resolve(raw), "an expired token must fail closed (mutation: drop the expiry check → this reds)")
        clock = 5_000
        assertNull(s.resolve(raw))
    }

    @Test
    fun revoke_immediatelyDenies() {
        val s = store()
        val raw = s.mint("gone")
        assertTrue(s.revoke(raw), "revoke drops the token")
        assertNull(s.resolve(raw), "revoked → immediate deny")
        assertFalse(s.revoke(raw), "revoke is idempotent (already gone)")
    }

    @Test
    fun revokeSubject_dropsAllTheirTokens() {
        val s = store()
        val a = s.mint("multi"); val b = s.mint("multi"); val other = s.mint("keep")
        assertEquals(2, s.revokeSubject("multi"))
        assertNull(s.resolve(a)); assertNull(s.resolve(b))
        assertEquals("keep", s.resolve(other)?.subject, "another subject's token is untouched")
    }

    @Test
    fun rawToken_isNeverStored_hashedAtRest() {
        val s = store()
        val raw = s.mint("secret-subject")
        assertFalse(raw in s.storedKeys(), "the raw token must NOT be a stored key — the store holds only its hash (mutation: store the raw instead of hash → this reds)")
        assertEquals(1, s.count())
        // the hash is deterministic (resolve works) yet not the raw value
        assertTrue(s.storedKeys().single() != raw)
    }
}
