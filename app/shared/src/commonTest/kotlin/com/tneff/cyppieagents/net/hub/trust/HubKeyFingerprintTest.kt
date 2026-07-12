package com.tneff.cyppieagents.net.hub.trust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-478 — the fingerprint stack that the OOB confirmation rests on. The SHA-256 is pinned against the NIST
 * vectors (empty, "abc", and a message that crosses the 56-byte padding boundary) so a transcription bug can't
 * slip a wrong-but-deterministic digest past; the fingerprint format is pinned to colon-separated lowercase hex
 * of the digest (the SSH-`known_hosts` convention the seam uses).
 */
class HubKeyFingerprintTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    @Test
    fun sha256_matchesNistVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(Sha256.digest(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(Sha256.digest("abc".encodeToByteArray())),
        )
        // 56-byte message → forces a second padding block; catches length/padding bugs.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun fingerprint_isColonHexOfSha256_realVector() {
        // SHA-256 of 32 zero bytes = 66687aad…2925 → colon-separated lowercase hex.
        val expected = "66:68:7a:ad:f8:62:bd:77:6c:8f:c1:8b:8e:9f:8e:20:" +
            "08:97:14:85:6e:e2:33:b3:90:2a:59:1d:0d:5f:29:25"
        assertEquals(expected, HubKeyFingerprint.of(ByteArray(32)))
    }

    @Test
    fun fingerprint_hasThirtyTwoTwoHexGroups() {
        val groups = HubKeyFingerprint.of(ByteArray(32) { it.toByte() }).split(":")
        assertEquals(32, groups.size)
        assertTrue(groups.all { it.length == 2 && it.all { c -> c in "0123456789abcdef" } })
    }

    @Test
    fun fingerprint_isDeterministic_andKeySensitive() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertEquals(HubKeyFingerprint.of(a), HubKeyFingerprint.of(a.copyOf()))
        assertNotEquals(HubKeyFingerprint.of(a), HubKeyFingerprint.of(b))
    }
}
