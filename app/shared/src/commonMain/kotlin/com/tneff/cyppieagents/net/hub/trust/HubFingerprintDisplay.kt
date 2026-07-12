package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64

/**
 * CYP-482 — the **transport-independent** derivation of a hub key's human-comparable fingerprint (CYP-480
 * §2.1), for the OOB trust confirmation:
 *  - a **word sequence** (the canonical PGP biometric word list) — the *primary*, **security-bearing** form an
 *    operator reads aloud / compares against the hub console;
 *  - **hex** — the *secondary*, copyable form (reuses the merged CYP-478 [HubKeyFingerprint]);
 *  - a **QR payload** — the machine-readable scan path.
 *
 * All fold the **same** `SHA-256(hubStatic)` digest → word/hex/QR always agree; pure + deterministic +
 * identical on every target → **vector-pinnable** (like [Sha256]); **no RR5 / live-fingerprint dependency**
 * (the S-B confirm/reject screen renders these once the real `dhPubKey` flows — HA: never a placeholder).
 *
 * **Entropy (Reviewer threat-model AC).** Each token is one digest byte (8 bit) → one word from a 256-word
 * list; [DEFAULT_TOKEN_COUNT] = **11 tokens × 8 bit = 88 bit**, comfortably over the ≥80-bit
 * OOB-substitution-resistance floor (never below 6/66). A ≥2048-word list would NOT help — a byte only reaches
 * `0..255` (8 bit/token) regardless — so the PGP **256**-word lists (which divide 256 exactly → zero modulo
 * bias) are used, alternating [PgpWordList.EVEN]/[PgpWordList.ODD] by position (the PGP transposition guard).
 * **Emoji is not a fingerprint form** (too few bits to be bit-bearing).
 */
object HubFingerprintDisplay {

    /** Colon-hex of `SHA-256(hubStatic)` (secondary, copyable) — reuses the merged CYP-478 primitive. */
    fun hex(hubStatic: ByteArray): String = HubKeyFingerprint.of(hubStatic)

    /**
     * The [count] leading digest bytes as indices `0..255` — the content-independent mapping the word sequence
     * looks up. Deterministic: identical inputs → identical indices on every target.
     */
    fun indices(hubStatic: ByteArray, count: Int): List<Int> {
        require(count in 1..32) { "count must be 1..32 (SHA-256 is 32 bytes)" }
        val digest = Sha256.digest(hubStatic)
        return (0 until count).map { digest[it].toInt() and 0xff }
    }

    /**
     * The PGP-word fingerprint sequence: each of the first [count] digest bytes selects a word, **alternating
     * the [PgpWordList.EVEN] (even position) and [PgpWordList.ODD] (odd position) 256-word lists** —
     * `token[i] = (i even ? EVEN : ODD)[byte]`. The position-parity alternation is the PGP
     * transposition/duplication/omission guard (a swapped pair lands a word in the wrong list). No modulo bias:
     * each list has exactly 256 entries, one per byte value.
     */
    fun words(hubStatic: ByteArray, count: Int = DEFAULT_TOKEN_COUNT): List<String> =
        indices(hubStatic, count).mapIndexed { i, b ->
            (if (i % 2 == 0) PgpWordList.EVEN else PgpWordList.ODD)[b]
        }

    /**
     * The canonical, machine-readable QR **payload**: `"$QR_SCHEME:<base64(hubStatic)>"`. A scanner re-derives
     * the same fingerprint from the raw key; the actual QR *bitmap* rendering is an S-B / UI concern.
     */
    fun qrPayload(hubStatic: ByteArray): String = "$QR_SCHEME:${Base64.Default.encode(hubStatic)}"

    const val QR_SCHEME: String = "cyppie-hub-key"

    /** 11 tokens × 8 bit = **88 bit** ≥ the 80-bit OOB floor (Reviewer threat-model AC). Never below 6/66. */
    const val DEFAULT_TOKEN_COUNT: Int = 11
}
