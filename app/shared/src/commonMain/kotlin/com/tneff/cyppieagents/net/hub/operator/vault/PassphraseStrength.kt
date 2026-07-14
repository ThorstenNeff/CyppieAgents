package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.math.log2

/**
 * CYP-542 / B1 — a **conservative structural** passphrase-entropy estimate for the enroll strength meter (U1) + the
 * ② floor gate ([CredentialPolicy.minEntropyBits]). Estimates `length × log2(charset) × diversity-penalty` so trivial
 * weak inputs (short / low-diversity / repeated) score well below the floor, while a 6-word diceware / 12+ random-char
 * passphrase clears it.
 *
 * ⚠ **Limitation (flagged for Reviewer/UIUX convergence):** this is a STRUCTURAL estimate — it does NOT detect
 * dictionary words / common phrases (e.g. it would overestimate a well-known quote). A **dictionary-grade estimator
 * (zxcvbn)** is the recommended hardening for the meter; that is a UIUX-U1 + Reviewer decision (another dep). Until
 * then the structural estimate + the diceware/12+-random UX guidance carry the floor; the vault's ① Argon2id cost is
 * the crypto backstop. Never presents a weak passphrase as strong: the meter is advisory, the gate is [meets].
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

    /** The ② gate: does the passphrase meet [minBits] (the [CredentialPolicy] floor, 64 on the no-hardware path)? */
    fun meets(passphrase: CharArray, minBits: Int): Boolean = estimateBits(passphrase) >= minBits

    private fun charsetSize(p: CharArray): Int {
        var s = 0
        if (p.any { it in 'a'..'z' }) s += 26
        if (p.any { it in 'A'..'Z' }) s += 26
        if (p.any { it in '0'..'9' }) s += 10
        if (p.any { !it.isLetterOrDigit() }) s += 33 // punctuation/space/symbols (approx printable-ASCII symbol set)
        return s.coerceAtLeast(2)
    }
}
