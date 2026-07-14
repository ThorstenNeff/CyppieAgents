package com.tneff.cyppieagents.net.hub.operator.vault

import kotlin.math.log2

/**
 * CYP-542 / B1 (#1, Reviewer-gated) — the **generated diceware** passphrase: the prominent one-click default so the
 * strong path is the path of least resistance (the no-hardware security rests on it). Uniform-random over the **EFF
 * Large Wordlist** (exactly 7776 = 6^5 words) → `words × log2(7776)` bits; ≥6 words ⇒ **≥77 bit by construction**,
 * independent of any meter/dictionary weakness.
 *
 * **Fail-closed asset guard (no-shortcut):** the ctor REQUIRES exactly [EFF_LARGE_SIZE] **unique** words. A wrong /
 * truncated / duplicated wordlist can silently collapse the entropy (a 3000-word list ⇒ ~11.5 bit/word), so a
 * non-canonical list is REFUSED here rather than generating a weak passphrase — the diceware default is then
 * unavailable (the UX falls back to type-your-own), never weak.
 *
 * **Uniform, no modulo bias (Reviewer):** [uniformIndex] uses **rejection sampling** on 13 random bits (`[0,8192)`),
 * discarding `[7776,8192)`, so every word is equiprobable — a naive `random % 7776` would bias the first 416 words.
 * The randomness is the injected CS-random [secureBytes] (jvm = `SecureRandom`).
 */
class DicewareGenerator(
    private val wordlist: List<String>,
    private val secureBytes: SecureBytes,
) {
    init {
        require(wordlist.size == EFF_LARGE_SIZE && wordlist.toHashSet().size == EFF_LARGE_SIZE) {
            "diceware wordlist must be exactly $EFF_LARGE_SIZE unique words (the canonical EFF Large Wordlist) — refusing a non-canonical list (would weaken entropy)"
        }
    }

    /** Generate an [words]-word (≥[MIN_WORDS]) space-joined passphrase as a `CharArray` (H-1, never a `String`). */
    fun generate(words: Int = DEFAULT_WORDS): CharArray {
        require(words >= MIN_WORDS) { "diceware needs ≥ $MIN_WORDS words for the ≥77-bit floor" }
        val picked = ArrayList<String>(words)
        repeat(words) { picked.add(wordlist[uniformIndex()]) }
        val total = picked.sumOf { it.length } + (picked.size - 1) // words + single spaces
        val out = CharArray(total)
        var i = 0
        picked.forEachIndexed { idx, w ->
            if (idx > 0) out[i++] = ' '
            w.toCharArray().copyInto(out, i)
            i += w.length
        }
        return out
    }

    /** Uniform index in `[0, EFF_LARGE_SIZE)` via rejection sampling on 13 bits — equiprobable, no modulo bias. */
    private fun uniformIndex(): Int {
        while (true) {
            val b = secureBytes.bytes(2)
            val v = (((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)) and 0x1FFF // 13 bits: [0, 8192)
            if (v < EFF_LARGE_SIZE) return v // reject [7776, 8192) → uniform over [0, 7776)
        }
    }

    companion object {
        const val EFF_LARGE_SIZE = 7776 // 6^5 — the canonical EFF Large Wordlist size
        const val DEFAULT_WORDS = 6
        const val MIN_WORDS = 6

        /** The guaranteed entropy of a [words]-word diceware passphrase (bits). 6 words ⇒ ~77.5 bit. */
        fun entropyBits(words: Int): Int = (words * log2(EFF_LARGE_SIZE.toDouble())).toInt()
    }
}

/** CS-random bytes seam (jvm actual = `SecureRandom`; tests inject a deterministic source). Used by the diceware draw. */
fun interface SecureBytes {
    fun bytes(n: Int): ByteArray
}
