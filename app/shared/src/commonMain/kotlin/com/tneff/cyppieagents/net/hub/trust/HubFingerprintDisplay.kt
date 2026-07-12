package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64

/**
 * CYP-482 S-A — the **transport-independent** derivation of a hub key's human-comparable fingerprint in three
 * representations (CYP-480 §2.1), for the OOB trust confirmation:
 *  - a **word / emoji** sequence — the *primary*, error-resistant compare an operator reads aloud;
 *  - **hex** — the *secondary*, copyable form (reuses the merged CYP-478 [HubKeyFingerprint]);
 *  - a **QR payload** — the machine-readable scan path.
 *
 * All three fold the **same** `SHA-256(hubStatic)` digest, so word/emoji/hex/QR always agree. Pure +
 * deterministic + identical on every target → **vector-pinnable** (like [Sha256]); **no RR5 / live-fingerprint
 * dependency**. The S-B confirm/reject screen renders these once the real `dhPubKey` flows (HA: never a
 * placeholder fingerprint) — this is only the derivation.
 *
 * The word/emoji **content** and the **sequence length** are a **DS / security parameter** (list size sets the
 * bits-per-token, hence the OOB collision resistance). [DEFAULT_FINGERPRINT_WORDS] / [DEFAULT_FINGERPRINT_EMOJI]
 * / [DEFAULT_TOKEN_COUNT] are **functional placeholders** — DS finalizes them. The derivation *mechanism*
 * ([indices], digest→byte-indices) is content-independent and is what the vector teeth pin.
 */
object HubFingerprintDisplay {

    /** Colon-hex of `SHA-256(hubStatic)` (secondary, copyable) — reuses the merged CYP-478 primitive. */
    fun hex(hubStatic: ByteArray): String = HubKeyFingerprint.of(hubStatic)

    /**
     * The [count] leading digest bytes as indices `0..255` — the content-independent mapping the word/emoji
     * sequences look up. Deterministic: identical inputs → identical indices on every target.
     */
    fun indices(hubStatic: ByteArray, count: Int): List<Int> {
        require(count in 1..32) { "count must be 1..32 (SHA-256 is 32 bytes)" }
        val digest = Sha256.digest(hubStatic)
        return (0 until count).map { digest[it].toInt() and 0xff }
    }

    /** The word sequence: each of the first [count] digest bytes mapped into [wordlist] (index mod list size). */
    fun words(
        hubStatic: ByteArray,
        wordlist: List<String> = DEFAULT_FINGERPRINT_WORDS,
        count: Int = DEFAULT_TOKEN_COUNT,
    ): List<String> {
        require(wordlist.isNotEmpty()) { "wordlist must not be empty" }
        return indices(hubStatic, count).map { wordlist[it % wordlist.size] }
    }

    /** The emoji sequence — the same mechanism as [words], over an emoji list. */
    fun emoji(
        hubStatic: ByteArray,
        emojiList: List<String> = DEFAULT_FINGERPRINT_EMOJI,
        count: Int = DEFAULT_TOKEN_COUNT,
    ): List<String> {
        require(emojiList.isNotEmpty()) { "emojiList must not be empty" }
        return indices(hubStatic, count).map { emojiList[it % emojiList.size] }
    }

    /**
     * The canonical, machine-readable QR **payload**: `"$QR_SCHEME:<base64(hubStatic)>"`. A scanner re-derives
     * the same fingerprint from the raw key; the actual QR *bitmap* rendering is an S-B / UI concern.
     */
    fun qrPayload(hubStatic: ByteArray): String = "$QR_SCHEME:${Base64.Default.encode(hubStatic)}"

    const val QR_SCHEME: String = "cyppie-hub-key"

    /** Placeholder default sequence length — DS/security owns the final count (with the list size). */
    const val DEFAULT_TOKEN_COUNT: Int = 6

    /**
     * Placeholder word list (32 distinct maritime nouns, echoing the app theme) — **DS/security owns the
     * production content + size**. The mechanism is content-independent; swapping this list changes only the
     * rendered words, not the underlying (hex/QR-anchored) fingerprint.
     */
    val DEFAULT_FINGERPRINT_WORDS: List<String> = listOf(
        "anchor", "harbor", "compass", "beacon", "tide", "coral", "marlin", "breeze",
        "cargo", "lantern", "mariner", "current", "dolphin", "sextant", "ballast", "pier",
        "galley", "rudder", "plankton", "sonar", "keel", "buoy", "fathom", "seagull",
        "lagoon", "mast", "reef", "sail", "storm", "wharf", "kelp", "nautilus",
    )

    /** Placeholder emoji list (32 distinct) — **DS/security owns the production content + size**. */
    val DEFAULT_FINGERPRINT_EMOJI: List<String> = listOf(
        "⚓", "🧭", "🌊", "🐬", "🐟", "🦈", "🐚", "🦀",
        "🐙", "⛵", "🚢", "🛟", "🪝", "🌅", "🌙", "⭐",
        "🔱", "🦭", "🐋", "🌴", "🦩", "🪸", "🌀", "⚡",
        "🧊", "🔔", "📡", "🦑", "🏝", "🌧", "🐡", "🐳",
    )
}
