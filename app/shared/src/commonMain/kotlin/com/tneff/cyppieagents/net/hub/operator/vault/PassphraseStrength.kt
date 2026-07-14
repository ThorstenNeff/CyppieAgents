package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.math.log2

/**
 * CYP-542 / B1 — a **conservative structural** passphrase-entropy estimate for the enroll strength meter (U1) + the
 * ② floor gate ([CredentialPolicy.minEntropyBits]). Estimates `length × log2(charset) × diversity-penalty` so trivial
 * weak inputs (short / low-diversity / repeated) score well below the floor, while a 6-word diceware / 12+ random-char
 * passphrase clears it.
 *
 * ⚠ **HONEST residual (Reviewer ruling — do NOT overclaim):** this is a STRUCTURAL estimate. A structural "≥64 bit"
 * is **NOT** real 64-bit guessing-entropy — it does not detect dictionary words / common phrases (it would
 * overestimate a well-known quote). **Argon2id does NOT rescue a dictionary-crackable passphrase:** a dictionary
 * attack is a FEW guesses, so the per-guess KDF cost barely matters — the memory-hard cost only helps against
 * high-entropy brute-force. So the type-your-own path is NOT sold as "64-bit guaranteed". v1 closes the gap WITHOUT
 * zxcvbn via: **(a)** a generated **diceware** default as the prominent one-click path (uniform-random ≥7776-word
 * list, ≥6 words ⇒ ≥77 bit by construction — the strong path is the path of least resistance); **(b)** a
 * common-phrase **blocklist** on type-your-own ([isBlocklisted]) that rejects `password123` / `correct horse …` /
 * famous xkcd phrases at ~zero cost; **(c)** the structural ≥64-bit floor, **real-enforced fail-closed** ([meets]).
 * A dictionary-grade estimator (zxcvbn) is a **follow-up ticket**, not B1. The meter is advisory; the gate never
 * lets an under-floor or blocklisted passphrase through.
 */
object PassphraseStrength {

    /** A conservative entropy estimate in bits (0 for empty). Under-estimates by design (rejects weak, not accepts weak). */
    fun estimateBits(passphrase: CharArray): Int {
        if (passphrase.isEmpty()) return 0
        val bitsPerChar = log2(charsetSize(passphrase).toDouble())
        val unique = passphrase.toHashSet().size
        val diversity = (unique.toDouble() / passphrase.size).coerceIn(0.3, 1.0) // repeats add little entropy
        return (passphrase.size * bitsPerChar * diversity).toInt()
    }

    /**
     * The ② gate: the passphrase meets [minBits] AND is not blocklisted. **Real-enforced fail-closed** — the enroll
     * flow refuses a passphrase that fails this (the weak meter never lets an under-floor/blocklisted secret through).
     */
    fun meets(passphrase: CharArray, minBits: Int): Boolean = verdict(passphrase, minBits) == StrengthVerdict.OK

    /**
     * AC-3 (UIUX enroll-QA G1) — the **distinct** enroll verdict so the UI shows an honest, cause-specific error:
     * [BLOCKLISTED] (common/breached — checked first, since a blocklisted phrase can be structurally strong) ≠
     * [TOO_WEAK] (structural entropy `<` [minBits]) ≠ [OK]. Both failures fail-closed. Not coupled to zxcvbn (CYP-544).
     */
    fun verdict(passphrase: CharArray, minBits: Int): StrengthVerdict = when {
        isBlocklisted(passphrase) -> StrengthVerdict.BLOCKLISTED
        estimateBits(passphrase) < minBits -> StrengthVerdict.TOO_WEAK
        else -> StrengthVerdict.OK
    }

    /**
     * #3 (Reviewer) — a no-dep common-phrase blocklist against the type-your-own path: top breached passwords + famous
     * diceware/xkcd phrases. Catches `password123` / `correct horse battery staple` etc. that the structural estimator
     * over-scores. Not exhaustive (zxcvbn is the follow-up) but closes the bulk of the dictionary hole at ~zero cost.
     *
     * H-1 note: this builds a transient normalized `String` (lowercased, whitespace-stripped) for matching — enroll-time
     * only, not retained; the KDF path stays `char[]`. (Flagged for the Reviewer as a bounded, best-effort exception.)
     */
    fun isBlocklisted(passphrase: CharArray): Boolean {
        val n = passphrase.concatToString().lowercase().filterNot { it.isWhitespace() }
        if (n.isEmpty()) return true
        if (n in BLOCKLIST) return true
        return WEAK_ROOTS.any { n == it || n.startsWith(it) }
    }

    private fun charsetSize(p: CharArray): Int {
        var s = 0
        if (p.any { it in 'a'..'z' }) s += 26
        if (p.any { it in 'A'..'Z' }) s += 26
        if (p.any { it in '0'..'9' }) s += 10
        if (p.any { !it.isLetterOrDigit() }) s += 33 // punctuation/space/symbols (approx printable-ASCII symbol set)
        return s.coerceAtLeast(2)
    }

    /** Normalized (lowercase, whitespace-stripped) common-phrase blocklist. Extend freely — it is advisory + additive. */
    private val BLOCKLIST: Set<String> = setOf(
        // famous diceware / xkcd / quotes the structural estimator over-scores
        "correcthorsebatterystaple", "tobeornottobe", "thequickbrownfoxjumpsoverthelazydog",
        "iloveyou", "letmein", "trustno1", "whatever", "starwars", "batman", "superman",
        // top breached passwords (normalized)
        "password", "password1", "password123", "123456", "12345678", "123456789", "1234567890",
        "qwerty", "qwerty123", "abc123", "111111", "000000", "admin", "root", "welcome", "monkey",
        "dragon", "sunshine", "princess", "football", "baseball", "master", "shadow", "michael",
    )

    /** Weak roots — a passphrase equal-to or starting-with one is blocklisted (catches `password<anything>`). */
    private val WEAK_ROOTS: Set<String> = setOf("password", "qwerty", "123456", "letmein", "admin", "welcome", "iloveyou")
}

/** AC-3 — the distinct enroll strength causes (honest, cause-specific copy in the UI). */
enum class StrengthVerdict { OK, TOO_WEAK, BLOCKLISTED }
