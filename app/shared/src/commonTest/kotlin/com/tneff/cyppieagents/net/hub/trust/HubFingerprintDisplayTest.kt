package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-482 S-A — the fingerprint-derivation teeth. The **mechanism** ([HubFingerprintDisplay.indices]) is
 * hard-vector-pinned (content-independent, like [Sha256]); word/emoji assertions pin the *relationship* to the
 * digest (robust to a DS list swap); hex reuses the merged CYP-478 primitive; the QR payload round-trips to the
 * raw key. All representations fold the same digest, so they agree and are key-sensitive.
 */
class HubFingerprintDisplayTest {

    // SHA-256 of 32 zero bytes = 66:68:7a:ad:f8:62… → the first 6 bytes as unsigned ints.
    private val zeros = ByteArray(32)
    private val zerosIndices6 = listOf(0x66, 0x68, 0x7a, 0xad, 0xf8, 0x62) // 102,104,122,173,248,98

    @Test
    fun indices_areVectorPinned_andBounded() {
        assertEquals(zerosIndices6, HubFingerprintDisplay.indices(zeros, 6))
        // every index is a byte value 0..255, count honoured
        val all = HubFingerprintDisplay.indices(ByteArray(32) { it.toByte() }, 12)
        assertEquals(12, all.size)
        assertTrue(all.all { it in 0..255 })
    }

    @Test
    fun hex_reusesHubKeyFingerprint() {
        assertEquals(HubKeyFingerprint.of(zeros), HubFingerprintDisplay.hex(zeros))
    }

    @Test
    fun qrPayload_isSchemeTaggedBase64_roundTrips() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val payload = HubFingerprintDisplay.qrPayload(key)
        assertTrue(payload.startsWith("${HubFingerprintDisplay.QR_SCHEME}:"))
        val decoded = Base64.Default.decode(payload.substringAfter(":"))
        assertContentEquals(key, decoded, "the QR payload must carry the raw hub key, scannable+re-derivable")
    }

    @Test
    fun words_followTheDigest_correctCount_allInList() {
        val wl = HubFingerprintDisplay.DEFAULT_FINGERPRINT_WORDS
        val words = HubFingerprintDisplay.words(zeros)
        assertEquals(HubFingerprintDisplay.DEFAULT_TOKEN_COUNT, words.size)
        assertTrue(words.all { it in wl })
        // relationship (robust to a DS list swap): word[i] == wordlist[digestByte[i] mod size]
        val expected = HubFingerprintDisplay.indices(zeros, HubFingerprintDisplay.DEFAULT_TOKEN_COUNT).map { wl[it % wl.size] }
        assertEquals(expected, words)
    }

    @Test
    fun emoji_followTheDigest_sameMechanism() {
        val el = HubFingerprintDisplay.DEFAULT_FINGERPRINT_EMOJI
        val emoji = HubFingerprintDisplay.emoji(zeros)
        assertEquals(HubFingerprintDisplay.DEFAULT_TOKEN_COUNT, emoji.size)
        assertTrue(emoji.all { it in el })
        val expected = HubFingerprintDisplay.indices(zeros, HubFingerprintDisplay.DEFAULT_TOKEN_COUNT).map { el[it % el.size] }
        assertEquals(expected, emoji)
    }

    @Test
    fun deterministic_andKeySensitive() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertEquals(HubFingerprintDisplay.words(a), HubFingerprintDisplay.words(a.copyOf()))
        assertNotEquals(HubFingerprintDisplay.words(a), HubFingerprintDisplay.words(b))
        assertNotEquals(HubFingerprintDisplay.hex(a), HubFingerprintDisplay.hex(b))
        assertNotEquals(HubFingerprintDisplay.qrPayload(a), HubFingerprintDisplay.qrPayload(b))
    }

    @Test
    fun customListAndCount_areHonoured() {
        val list = listOf("x", "y", "z")
        val out = HubFingerprintDisplay.words(zeros, list, count = 4)
        assertEquals(4, out.size)
        assertTrue(out.all { it in list })
    }

    @Test
    fun defaultLists_haveNoDuplicates() {
        val wl = HubFingerprintDisplay.DEFAULT_FINGERPRINT_WORDS
        val el = HubFingerprintDisplay.DEFAULT_FINGERPRINT_EMOJI
        assertEquals(wl.size, wl.toSet().size, "word list must be distinct")
        assertEquals(el.size, el.toSet().size, "emoji list must be distinct")
    }
}
