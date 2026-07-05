package com.tneff.cyppieagents.avatar

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-215 — the **Reviewer-security-gate** teeth for the untrusted avatar-upload pipeline. Hermetic (no
 * I/O, no network): every branch of [AvatarImageProcessor] is hit with crafted bytes. The reject-matrix
 * proves fail-closed with the NAMED check (diagnostics); the happy path proves re-encode-strip +
 * center-crop-square + the minted ref. Mutation-verified: weaken any gate → the matching test goes RED.
 */
class AvatarImageProcessorTest {

    // ---- helpers: craft real + hostile bytes ----

    private fun realImage(w: Int, h: Int, fmt: String): ByteArray {
        val img = BufferedImage(w, h, if (fmt == "jpg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(10, 120, 200); g.fillRect(0, 0, w, h)
        g.color = Color(240, 240, 10); g.fillRect(w / 4, h / 4, w / 2, h / 2) // an off-centre block → crop is observable
        g.dispose()
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, fmt, out), "test could not encode a real $fmt")
        return out.toByteArray()
    }

    /** A structurally-valid PNG whose IHDR DECLARES [w]×[h] but carries no pixel data — the bomb probe: the
     *  header-dim gate must reject on the declared size WITHOUT ever decoding a pixel. */
    private fun pngDeclaring(w: Int, h: Int): ByteArray {
        fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun chunk(type: String, data: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(t); update(data) }.value.toInt()
            return be(data.size) + t + data + be(crc)
        }
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = be(w) + be(h) + byteArrayOf(8, 2, 0, 0, 0) // 8-bit, colour-type 2 (RGB), no compression/filter/interlace
        return sig + chunk("IHDR", ihdr) + chunk("IEND", ByteArray(0))
    }

    // ---- reject matrix (each fires a NAMED check) ----

    @Test fun empty_isRejected() {
        assertEquals("empty-body", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(ByteArray(0)) }.check)
    }

    @Test fun oversize_isRejected_beforeAnyDecode() {
        val tooBig = ByteArray(AvatarLimits.MAX_BYTES + 1) { 0x89.toByte() } // even with a PNG-ish first byte
        assertEquals("size-cap", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(tooBig) }.check)
    }

    @Test fun svg_isRejected_byMagicBytes_notExtension() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>".toByteArray()
        assertEquals("magic-bytes", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(svg) }.check)
    }

    @Test fun xmlDeclaredSvg_isRejected() {
        val svg = "<?xml version=\"1.0\"?><svg/>".toByteArray()
        assertEquals("magic-bytes", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(svg) }.check)
    }

    @Test fun gif_and_bmp_and_webp_areRejected() {
        val gif = "GIF89a".toByteArray() + ByteArray(20)
        val bmp = "BM".toByteArray() + ByteArray(20)
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(20)
        for (b in listOf(gif, bmp, webp)) {
            assertEquals("magic-bytes", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(b) }.check)
        }
    }

    @Test fun decompressionBomb_declaredHugePixels_isRejected_beforeDecode() {
        // 8000×8000 = 64M px (each side ≤ 8192, so the pixel-cap — not the side-cap — is what fires), yet the
        // file is ~60 bytes. Rejected on the header dims → no pixel buffer is ever allocated.
        val bomb = pngDeclaring(8000, 8000)
        assertEquals("pixel-cap", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(bomb) }.check)
    }

    @Test fun oversizeSide_isRejected() {
        val wide = pngDeclaring(9000, 8) // within the pixel cap but a side > MAX_SIDE (8192)
        assertEquals("dimension-cap", assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(wide) }.check)
    }

    @Test fun pngMagicButGarbageBody_isRejected_notCrash() {
        val garbage = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40) { 0x7F }
        // fails at header-dims or decode — the point is a fail-closed AvatarRejected, never an escaped crash.
        assertFailsWith<AvatarRejected> { AvatarImageProcessor.process(garbage) }
    }

    // ---- happy path: re-encode strip + center-crop-square + ref ----

    @Test fun png_happy_reEncodesTo256Square_withRef() {
        val src = realImage(400, 200, "png")
        val r = AvatarImageProcessor.process(src)
        assertEquals("png", r.srcFormat)
        assertEquals(400, r.srcWidth); assertEquals(200, r.srcHeight)
        assertEquals(AvatarLimits.OUT_SIZE, r.outWidth); assertEquals(AvatarLimits.OUT_SIZE, r.outHeight)
        assertTrue(r.ref.length == 12 && r.ref.all { it.isDigit() || it in 'a'..'f' }, "ref is a 12-hex content id: ${r.ref}")
        // The stored bytes really are a 256×256 PNG (decode the OUTPUT).
        val decoded = ImageIO.read(src.let { java.io.ByteArrayInputStream(r.png) })
        assertEquals(256, decoded.width); assertEquals(256, decoded.height)
    }

    @Test fun jpeg_happy_isAccepted_andReEncodedToPng() {
        val r = AvatarImageProcessor.process(realImage(300, 300, "jpg"))
        assertEquals("jpeg", r.srcFormat)
        // output is PNG regardless of source (magic-bytes of the OUTPUT are PNG).
        assertEquals(AvatarImageProcessor.SourceFormat.PNG, AvatarImageProcessor.sniff(r.png))
    }

    @Test fun reEncode_stripsAppendedPayload() {
        // A polyglot: a valid PNG with a secret appended after IEND. ImageIO ignores the trailing bytes; the
        // re-encoded output is built from the pixel buffer ONLY → the secret does NOT survive.
        val secret = "SECRET-EXFIL-PAYLOAD".toByteArray()
        val polyglot = realImage(64, 64, "png") + secret
        val r = AvatarImageProcessor.process(polyglot)
        assertFalse(indexOfSub(r.png, secret) >= 0, "the appended payload survived the re-encode (strip failed)")
    }

    @Test fun ref_isDeterministic_forIdenticalOutput() {
        val a = AvatarImageProcessor.process(realImage(128, 128, "png"))
        val b = AvatarImageProcessor.process(realImage(128, 128, "png"))
        assertEquals(a.ref, b.ref, "identical re-encoded bytes → identical content ref")
    }

    private fun indexOfSub(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
