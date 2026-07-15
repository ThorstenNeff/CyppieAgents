package com.tneff.cyppieagents.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-593 — the OIDC loopback success page must declare UTF-8, or the browser mis-decodes the UTF-8 bytes as Latin-1
 * (the live Mojibake: `Anmeldung abgeschlossen â€" zurÃ¼ck`). The bytes were always correct; the missing charset
 * declaration was the bug. These teeth pin the declaration and prove it is load-bearing (the body IS non-ASCII).
 */
class Cyp593LoopbackCharsetTest {

    @Test
    fun contentType_declaresUtf8() {
        // Reddening mutation: drop the `charset=utf-8` from the Content-Type ⇒ the browser falls back to Latin-1 ⇒ red.
        assertTrue(
            OIDC_LOOPBACK_RESPONSE_CONTENT_TYPE.lowercase().contains("charset=utf-8"),
            "the loopback response MUST declare charset=utf-8 (com.sun.net.httpserver sets no Content-Type by default)",
        )
    }

    @Test
    fun body_isNonAscii_soTheCharsetIsLoadBearing_andRoundTripsUtf8() {
        // The declaration only matters because the body carries non-ASCII (— em-dash, ü) — a guard that this stays true
        // (if the copy ever went ASCII-only, the tooth would be vacuous; this makes the coupling explicit).
        assertTrue(OIDC_LOOPBACK_RESPONSE_HTML.any { it.code > 127 }, "body has non-ASCII (—/ü) ⇒ the charset is load-bearing")
        assertTrue(OIDC_LOOPBACK_RESPONSE_HTML.contains("—") && OIDC_LOOPBACK_RESPONSE_HTML.contains("zurück"))
        // Sanity: UTF-8 encode→decode is lossless (the bytes were never the problem).
        assertEquals(OIDC_LOOPBACK_RESPONSE_HTML, OIDC_LOOPBACK_RESPONSE_HTML.encodeToByteArray().decodeToString())
        // Belt-and-suspenders: the HTML also carries a <meta charset> (independent of the HTTP header).
        assertTrue(OIDC_LOOPBACK_RESPONSE_HTML.lowercase().contains("charset=\"utf-8\""), "the <meta charset> is present too")
    }
}
