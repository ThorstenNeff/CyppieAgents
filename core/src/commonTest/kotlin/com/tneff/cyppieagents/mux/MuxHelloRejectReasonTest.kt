package com.tneff.cyppieagents.mux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-620 instrumentation — the logs-only [MuxHello.rejectReason] classifier used in the MuxBridge refuse WARN. Proves
 * the categories are correct AND (the no-secret-leak property) that it returns a stable category, never raw bytes.
 * Lives in `:core` commonTest alongside [MuxHello] (CYP-622: shared, one source of truth for both ends).
 */
class MuxHelloRejectReasonTest {

    @Test fun absent_whenNull() = assertEquals("absent", MuxHello.rejectReason(null))

    @Test fun badLength_whenWrongSize() {
        assertEquals("bad-length:3", MuxHello.rejectReason(byteArrayOf(1, 2, 3)))
        assertEquals("bad-length:0", MuxHello.rejectReason(ByteArray(0)))
    }

    @Test fun badMagic_whenMagicWrong() {
        val m = MuxHello.ENCODED.copyOf(); m[0] = 0x00 // corrupt 'C'
        assertEquals("bad-magic", MuxHello.rejectReason(m))
    }

    @Test fun versionSkew_reportsTheVersion() {
        val m = MuxHello.ENCODED.copyOf(); m[5] = (MuxHello.VERSION + 7).toByte()
        assertEquals("version-skew:${MuxHello.VERSION + 7}", MuxHello.rejectReason(m))
    }

    @Test fun modeMismatch_reportsTheMode() {
        val m = MuxHello.ENCODED.copyOf(); m[6] = 9 // an unknown mode
        assertEquals("mode-mismatch:9", MuxHello.rejectReason(m))
    }

    @Test fun ok_whenValid() = assertEquals("ok", MuxHello.rejectReason(MuxHello.ENCODED))

    @Test
    fun noSecretLeak_reasonIsACategory_neverRawBytes() {
        // A hello whose (post-magic) bytes are arbitrary "sensitive-looking" values → the reason must be a stable
        // category, carrying at most a benign numeric (size/version/mode), never the raw byte content.
        val sneaky = byteArrayOf('C'.code.toByte(), 'Y'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), 0x00, 0xAB.toByte(), 0xCD.toByte())
        val reason = MuxHello.rejectReason(sneaky)
        assertTrue(reason.startsWith("version-skew:"), "classified by category, not content: '$reason'")
        // the 0xCD mode byte and the arbitrary payload never appear as raw bytes in the reason string
        assertTrue(!reason.contains("«") && !reason.contains("Í"), "no raw hello bytes surface in the log reason")
    }
}
