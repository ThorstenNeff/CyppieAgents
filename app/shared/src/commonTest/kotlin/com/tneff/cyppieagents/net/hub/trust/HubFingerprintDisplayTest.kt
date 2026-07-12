package com.tneff.cyppieagents.net.hub.trust

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-482 — the fingerprint-derivation teeth. The **mechanism** ([HubFingerprintDisplay.indices]) is
 * hard-vector-pinned (content-independent, like [Sha256]); the word sequence pins the **PGP even/odd
 * position-parity alternation** + the **88-bit** length (Reviewer threat-model AC); hex reuses the merged
 * CYP-478 primitive; the QR payload round-trips to the raw key; and the vendored [PgpWordList] is pinned by
 * checksum (drift/typo guard) + canonical anchors.
 */
class HubFingerprintDisplayTest {

    // SHA-256 of 32 zero bytes = 66:68:7a:ad:f8:62… → the first 6 bytes as unsigned ints.
    private val zeros = ByteArray(32)
    private val zerosIndices6 = listOf(0x66, 0x68, 0x7a, 0xad, 0xf8, 0x62) // 102,104,122,173,248,98

    @Test
    fun indices_areVectorPinned_andBounded() {
        assertEquals(zerosIndices6, HubFingerprintDisplay.indices(zeros, 6))
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

    /**
     * The mandatory even/odd tooth: tokens at EVEN positions come from [PgpWordList.EVEN] and ODD positions
     * from [PgpWordList.ODD] — from **different** lists (a mutation using only the even list turns this RED) —
     * plus the 88-bit count (11 tokens × 8 bit).
     */
    @Test
    fun words_alternateEvenOddPgpLists_byPosition_88bit() {
        val key = ByteArray(32) { (it * 5 + 1).toByte() }
        val words = HubFingerprintDisplay.words(key)
        assertEquals(11, words.size)                                   // 11 × 8 bit = 88 bit ≥ 80-bit floor
        assertEquals(HubFingerprintDisplay.DEFAULT_TOKEN_COUNT, words.size)
        val idx = HubFingerprintDisplay.indices(key, 11)
        for (i in 0 until 11) {
            val list = if (i % 2 == 0) PgpWordList.EVEN else PgpWordList.ODD
            assertEquals(list[idx[i]], words[i], "position $i must come from the ${if (i % 2 == 0) "EVEN" else "ODD"} list")
        }
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
    fun pgpWordList_isCanonical_distinct_disjoint_checksumPinned() {
        assertEquals(256, PgpWordList.EVEN.size)
        assertEquals(256, PgpWordList.ODD.size)
        assertEquals(256, PgpWordList.EVEN.toSet().size, "EVEN must be distinct")
        assertEquals(256, PgpWordList.ODD.toSet().size, "ODD must be distinct")
        assertTrue(PgpWordList.ODD.none { it in PgpWordList.EVEN.toSet() }, "EVEN/ODD must be disjoint (position guard)")
        // canonical anchors (byte 0x00 / 0xFF)
        assertEquals("aardvark", PgpWordList.EVEN.first()); assertEquals("Zulu", PgpWordList.EVEN.last())
        assertEquals("adroitness", PgpWordList.ODD.first()); assertEquals("Yucatan", PgpWordList.ODD.last())
        // regression guard: recompute the pinned checksum over the embedded lists (a typo/drift turns this RED).
        val serialized = (PgpWordList.EVEN.joinToString("\n") + "\n" + PgpWordList.ODD.joinToString("\n")).encodeToByteArray()
        val hex = Sha256.digest(serialized).joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
        assertEquals(PgpWordList.PGP_LIST_SHA256, hex)
    }
}
