package com.tneff.cyppieagents.auth.operator

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-485 — the [BackupCodeStore] AC: 10 codes, each ≥80-bit Crockford-Base32; hub-only salted-hash (no plaintext
 * kept); single-use consumed atomically. The recovery factor that lets an operator re-enroll WITHOUT central-login.
 */
class Cyp485BackupCodeTest {

    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    @Test
    fun generate_tenCodes_each16Crockford_distinct() {
        val codes = BackupCodeStore().generate()
        assertEquals(10, codes.size, "10 backup codes")
        assertEquals(10, codes.toSet().size, "all codes are distinct")
        codes.forEach { c ->
            assertEquals(16, c.length, "16 Crockford-Base32 chars = 80 bits of entropy (≥80)")
            assertTrue(c.all { it in alphabet }, "code '$c' uses only the Crockford alphabet (no I/L/O/U)")
        }
    }

    @Test
    fun consume_validCode_firstUseTrue_reuseFalse_singleUse() {
        val store = BackupCodeStore()
        val code = store.generate().first()
        assertTrue(store.consume(code), "first use of a valid code succeeds")
        assertFalse(store.consume(code), "a reused code is rejected (single-use)")
        assertEquals(9, store.remaining(), "one code consumed")
    }

    @Test
    fun consume_wrongOrEmpty_rejected() {
        val store = BackupCodeStore()
        store.generate()
        assertFalse(store.consume("ZZZZZZZZZZZZZZZZ"), "a wrong code is rejected")
        assertFalse(store.consume(""), "an empty code is rejected")
        assertEquals(10, store.remaining(), "nothing consumed on a miss")
    }

    @Test
    fun consume_caseAndSeparatorInsensitive() {
        val store = BackupCodeStore()
        val code = store.generate().first()
        val typed = code.lowercase().chunked(4).joinToString("-") // as an operator might type it
        assertTrue(store.consume(typed), "a lowercase, hyphenated typed code still matches")
    }

    @Test
    fun consume_atomicSingleUse_underConcurrency() {
        val store = BackupCodeStore()
        val code = store.generate().first()
        val successes = AtomicInteger(0)
        val threads = (1..8).map { Thread { if (store.consume(code)) successes.incrementAndGet() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, successes.get(), "a code is spent EXACTLY once under concurrent consume (atomic single-use)")
    }
}
