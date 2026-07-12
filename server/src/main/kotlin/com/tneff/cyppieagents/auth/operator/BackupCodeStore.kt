package com.tneff.cyppieagents.auth.operator

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * CYP-485 (③ Recovery-Enforcement, Decision 2 / RR7 / RR2-B) — the operator's **offline backup codes**: the
 * hub-local factor that lets a lost-all-devices operator RECOVER (re-enroll a new device) **without central-login
 * alone** (a compromised central login must never re-enroll+seize the hub). The Control Plane never holds these.
 *
 * **AC (CYP-485):**
 *  - **10 codes**, each **≥ 80 bits** of entropy — 16 **Crockford-Base32** chars from 10 `SecureRandom` bytes.
 *  - The hub stores **ONLY a per-code salted SHA-256** (never the plaintext) — hub-only; the plaintext set is
 *    returned **once** at [generate] (shown to the operator) and never again.
 *  - **Single-use, consumed ATOMICALLY** — the first [consume] of a valid code succeeds; a reuse (or a wrong code)
 *    fails. Concurrency-safe (one lock), so a code cannot be double-spent.
 *
 * Crockford-Base32 is case-insensitive and treats `I/L→1`, `O→0` on input (typo leniency); the generated alphabet
 * excludes those, so a normalized input matches the stored hash. Constant-time hash compare ([MessageDigest.isEqual]).
 */
class BackupCodeStore(
    private val random: SecureRandom = SecureRandom(),
) {
    private class Entry(val salt: ByteArray, val hash: ByteArray, @Volatile var consumed: Boolean)

    private val entries = mutableListOf<Entry>()
    private val lock = ReentrantLock()

    /**
     * Generate [count] fresh codes, **replace** any prior set with their salted hashes, and return the plaintexts
     * ONCE. Each code = 16 Crockford-Base32 chars (≥80 bits). The returned plaintexts are the only time they exist
     * in the clear on the hub.
     */
    fun generate(count: Int = 10): List<String> = lock.withLock {
        entries.clear()
        (1..count).map {
            val raw = ByteArray(10).also { random.nextBytes(it) } // 80 bits
            val code = crockfordBase32(raw)                        // 16 chars
            val salt = ByteArray(16).also { random.nextBytes(it) }
            entries.add(Entry(salt, saltedHash(salt, code), consumed = false))
            code
        }
    }

    /** Atomically verify + consume [code]: `true` on the FIRST use of a valid, unconsumed code; `false` for a wrong
     *  code or a reuse. A valid code is spent exactly once (single-use). */
    fun consume(code: String): Boolean = lock.withLock {
        val norm = normalize(code)
        val entry = entries.firstOrNull { !it.consumed && MessageDigest.isEqual(it.hash, saltedHash(it.salt, norm)) }
            ?: return false
        entry.consumed = true
        true
    }

    fun remaining(): Int = lock.withLock { entries.count { !it.consumed } }

    private fun saltedHash(salt: ByteArray, code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt + normalize(code).encodeToByteArray())

    private fun normalize(code: String): String =
        code.uppercase().replace("-", "").replace(" ", "").replace('I', '1').replace('L', '1').replace('O', '0')

    private fun crockfordBase32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    private companion object {
        /** Crockford Base32 (no I, L, O, U). */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
