package com.tneff.cyppieagents.events

import java.security.SecureRandom
import java.util.random.RandomGenerator

/**
 * Minimal ULID generator (PRD §4 `id`): 48-bit timestamp + 80-bit randomness, Crockford base32,
 * 26 chars, lexicographically time-sortable. The total order is owned by `seq`; the ULID only needs
 * to be unique and roughly time-ordered, so intra-ms monotonicity is intentionally not enforced.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32, no I/L/O/U
    private val rng: RandomGenerator = SecureRandom()

    /** Generate a ULID for [timeMs]; [rnd] is injectable for deterministic tests. */
    fun generate(timeMs: Long, rnd: RandomGenerator = rng): String {
        val sb = StringBuilder(26)
        // 48-bit time → 10 base32 chars, most-significant first.
        var t = timeMs
        val timeChars = CharArray(10)
        for (i in 9 downTo 0) {
            timeChars[i] = ENCODING[(t and 0x1f).toInt()]
            t = t ushr 5
        }
        sb.append(timeChars)
        // 80-bit randomness → 16 base32 chars.
        repeat(16) { sb.append(ENCODING[rnd.nextInt(32)]) }
        return sb.toString()
    }
}
