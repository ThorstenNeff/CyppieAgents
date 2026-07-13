package com.tneff.cyppieagents.operator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-525 — the single-source Ed25519 public-key encoding helper (client-encode → wire → hub-persist → RR3-verify).
 * The SPKI↔raw round-trip is the anti-"grün-gebaut-nie-CONNECTED" tooth: if the two ends disagree on encoding, a
 * perfectly-signed PoP verifies against the wrong bytes and never CONNECTs. Locks both directions + the fail-closed
 * boundary + the defensive reader.
 */
class Cyp525Ed25519KeyTest {

    private val raw = ByteArray(ED25519_RAW_LEN) { it.toByte() }

    @Test
    fun rawToSpki_thenSpkiToRaw_isIdentity() {
        val spki = ed25519RawToSpki(raw)
        assertEquals(ED25519_SPKI_LEN, spki.size, "SPKI is 44 bytes (12-byte prefix + 32-byte key)")
        assertTrue(ed25519SpkiToRaw(spki).contentEquals(raw), "SPKI→raw recovers the exact 32-byte key")
    }

    @Test
    fun publicKeyToRaw_acceptsRawAndSpki() {
        assertTrue(ed25519PublicKeyToRaw(raw).contentEquals(raw), "raw-32B passes through unchanged")
        assertTrue(ed25519PublicKeyToRaw(ed25519RawToSpki(raw)).contentEquals(raw), "SPKI-44B is stripped to raw-32B")
    }

    @Test
    fun failClosed_onWrongLength() {
        assertFailsWith<IllegalArgumentException> { ed25519RawToSpki(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { ed25519SpkiToRaw(ByteArray(43)) }
        assertFailsWith<IllegalArgumentException> { ed25519PublicKeyToRaw(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { ed25519PublicKeyToRaw(ByteArray(0)) }
    }

    @Test
    fun spkiToRaw_rejectsWrongAlgorithmPrefix() {
        val bogus = ByteArray(ED25519_SPKI_LEN) { 0x11 } // 44 bytes but NOT an Ed25519 SPKI prefix
        assertFailsWith<IllegalArgumentException> { ed25519SpkiToRaw(bogus) }
    }
}
