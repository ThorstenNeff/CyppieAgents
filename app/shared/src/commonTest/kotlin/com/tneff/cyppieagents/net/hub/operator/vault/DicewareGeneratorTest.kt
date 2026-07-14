package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-542 / B1 (#1) — [DicewareGenerator] teeth: the fail-closed exactly-7776-unique asset guard (a non-canonical
 * list is REFUSED, never a weak passphrase), the ≥77-bit-by-construction entropy for ≥6 words, and rejection
 * sampling (no modulo bias — a value in `[7776,8192)` is discarded, not folded to a low word). A synthetic 7776-word
 * list stands in for the EFF asset (the real list is the flagged resource; the guard makes a wrong asset fail closed).
 */
class DicewareGeneratorTest {

    private val effSized = List(DicewareGenerator.EFF_LARGE_SIZE) { "w$it" } // synthetic, unique, exactly 7776

    /** Deterministic CS-random stub: yields the queued 13-bit indices (big-endian 2 bytes) for each draw. */
    private class QueuedBytes(indices: List<Int>) : SecureBytes {
        private val q = ArrayDeque(indices)
        override fun bytes(n: Int): ByteArray {
            val v = q.removeFirst()
            return byteArrayOf(((v shr 8) and 0xff).toByte(), (v and 0xff).toByte())
        }
    }

    @Test
    fun generate_produces6SpaceJoinedWords_asCharArray() {
        val gen = DicewareGenerator(effSized, QueuedBytes(listOf(0, 1, 2, 3, 4, 5)))
        val pass = gen.generate()
        assertEquals("w0 w1 w2 w3 w4 w5", pass.concatToString(), "6 uniform-random words, space-joined")
        assertEquals(5, pass.count { it == ' ' }, "6 words ⇒ 5 spaces")
    }

    @Test
    fun entropyBits_6words_meetsThe77BitFloor() {
        assertTrue(DicewareGenerator.entropyBits(6) >= 77, "6 × log2(7776) ≈ 77.5 bit")
    }

    @Test
    fun rejectsNonCanonicalWordlist_failClosed() {
        assertFailsWith<IllegalArgumentException> { DicewareGenerator(List(3000) { "w$it" }, QueuedBytes(emptyList())) }
        // Right size but a DUPLICATE ⇒ still refused (uniqueness matters for uniform entropy).
        val dup = List(DicewareGenerator.EFF_LARGE_SIZE) { "w${it.coerceAtMost(7774)}" } // last two collide
        assertFailsWith<IllegalArgumentException> { DicewareGenerator(dup, QueuedBytes(emptyList())) }
    }

    @Test
    fun rejectionSampling_discardsAbove7776_noModuloBias() {
        // First draw = 8191 (in [7776,8192)) MUST be rejected; a naive `% 7776` would fold it to word 415.
        val gen = DicewareGenerator(effSized, QueuedBytes(listOf(8191, 0, 1, 2, 3, 4, 5)))
        val pass = gen.generate().concatToString()
        assertEquals("w0 w1 w2 w3 w4 w5", pass, "the out-of-range 8191 draw is discarded (not folded to w415)")
        assertTrue("w415" !in pass, "no modulo bias")
    }

    @Test
    fun generate_requiresAtLeast6Words() {
        assertFailsWith<IllegalArgumentException> { DicewareGenerator(effSized, QueuedBytes(emptyList())).generate(5) }
    }
}
