package com.tneff.cyppieagents.avatar

import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.geometry.Positions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * CYP-215 (CYP-212 avatar backend) — the **security-critical** upload pipeline. Uploaded bytes are
 * fully UNTRUSTED; this turns them into a canonical, safe-to-store PNG or rejects fail-closed. It is a
 * pure function of the input bytes (no I/O, no routing) so the Reviewer-gate teeth can hit every branch
 * hermetically.
 *
 * Fail-closed order (cheapest + most-bounding checks first; decode LAST, and only when safe):
 *  1. **Size cap** — empty → reject; > [AvatarLimits.MAX_BYTES] → reject (the route ALSO reads with a hard
 *     ceiling so a lying Content-Length can't blow past it).
 *  2. **Magic-bytes** on the ACTUAL bytes — never the filename / extension / `Content-Type` (all
 *     client-controlled). Only PNG + JPEG pass; **SVG is explicitly rejected** (XML → XXE / `<script>` /
 *     external refs). webp is NOT accepted (JVM ImageIO has no built-in webp reader; accepting it would
 *     force a native libwebp = CVE-heavy attack surface — a later add behind a vetted decoder if approved).
 *  3. **Header-dimension cap BEFORE full decode** (the decompression-bomb defence) — read `getWidth/
 *     getHeight` from the header only; reject `w*h` > [AvatarLimits.MAX_PIXELS] or a side > [MAX_SIDE].
 *     A 10 KB PNG can *claim* 50000×50000 → OOM on decode; this stops it before any pixel buffer.
 *  4. **Bounded decode** → BufferedImage (now safe dims).
 *  5. **Center-crop-square + downsize** to [AvatarLimits.OUT_SIZE]².
 *  6. **Re-encode to canonical PNG** — the output is built from the decoded pixel buffer ONLY, so EXIF /
 *     metadata / any appended-payload does not survive. The re-encoded bytes are what gets stored; the
 *     original upload is NEVER persisted.
 *  7. A [ref] (content hash of the re-encoded PNG) is minted server-side — the opaque, cache-busting id.
 *
 * Any decode error → [AvatarRejected] (never a partial result). The route maps every reject to a UNIFORM
 * client 400 (no parser-mapping leak) while LOGGING the described [AvatarRejected.check] (diagnostics).
 */
object AvatarImageProcessor {

    /** Sniffed source format (post magic-bytes) — the ONLY two we accept. */
    enum class SourceFormat(val label: String) { PNG("png"), JPEG("jpeg") }

    fun process(bytes: ByteArray): AvatarProcessResult {
        // 1. size
        if (bytes.isEmpty()) throw AvatarRejected("empty-body", "upload body is empty")
        if (bytes.size > AvatarLimits.MAX_BYTES) {
            throw AvatarRejected("size-cap", "upload ${bytes.size}B exceeds cap ${AvatarLimits.MAX_BYTES}B")
        }

        // 2. magic-bytes (authoritative — not the filename/Content-Type)
        val fmt = sniff(bytes) ?: throw AvatarRejected("magic-bytes", "unrecognised/disallowed magic ${hexPrefix(bytes)}")

        // 3. header-dimension gate (bomb defence) + 4. bounded decode — one reader, header first.
        val (image, srcW, srcH) = decodeGated(bytes, fmt)

        // 5. center-crop-square + downsize, 6. re-encode canonical PNG (metadata/payload strip).
        val out = ByteArrayOutputStream()
        Thumbnails.of(image)
            .crop(Positions.CENTER)
            .size(AvatarLimits.OUT_SIZE, AvatarLimits.OUT_SIZE)
            .outputFormat("png")
            .toOutputStream(out)
        val png = out.toByteArray()
        if (png.isEmpty()) throw AvatarRejected("re-encode", "re-encode produced no bytes")

        // 7. mint the opaque, cache-busting ref (content hash of the STORED bytes).
        return AvatarProcessResult(
            png = png,
            bytesIn = bytes.size,
            bytesOut = png.size,
            srcFormat = fmt.label,
            srcWidth = srcW,
            srcHeight = srcH,
            outWidth = AvatarLimits.OUT_SIZE,
            outHeight = AvatarLimits.OUT_SIZE,
            ref = contentRef(png),
        )
    }

    /** Magic-bytes sniff on the real bytes. PNG `89 50 4E 47 0D 0A 1A 0A`, JPEG `FF D8 FF`. Else null. */
    internal fun sniff(b: ByteArray): SourceFormat? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() &&
            b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte() -> SourceFormat.PNG
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> SourceFormat.JPEG
        else -> null // SVG (`<?xml`/`<svg`), GIF, webp (`RIFF….WEBP`), BMP, … all fall here → rejected.
    }

    /**
     * Reads header dims WITHOUT decoding pixels, enforces the pixel/side caps, THEN does the bounded
     * decode — using a reader for the SNIFFED format (never one ImageIO auto-picks for surprise bytes).
     */
    private fun decodeGated(bytes: ByteArray, fmt: SourceFormat): Triple<java.awt.image.BufferedImage, Int, Int> {
        val iis = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw AvatarRejected("no-stream", "could not open an image stream")
        try {
            val reader = ImageIO.getImageReadersByFormatName(fmt.label).asSequence().firstOrNull()
                ?: throw AvatarRejected("no-reader", "no ImageIO reader for ${fmt.label}")
            try {
                reader.setInput(iis, true, true)
                val w = try { reader.getWidth(0) } catch (e: Exception) { throw AvatarRejected("no-dimensions", "header dims unreadable: ${e.message}") }
                val h = try { reader.getHeight(0) } catch (e: Exception) { throw AvatarRejected("no-dimensions", "header dims unreadable: ${e.message}") }
                if (w <= 0 || h <= 0) throw AvatarRejected("bad-dimensions", "non-positive dims ${w}x${h}")
                if (w > AvatarLimits.MAX_SIDE || h > AvatarLimits.MAX_SIDE) {
                    throw AvatarRejected("dimension-cap", "side ${w}x${h} exceeds ${AvatarLimits.MAX_SIDE}")
                }
                if (w.toLong() * h.toLong() > AvatarLimits.MAX_PIXELS) {
                    throw AvatarRejected("pixel-cap", "$w*$h=${w.toLong() * h} exceeds ${AvatarLimits.MAX_PIXELS} px (bomb)")
                }
                val img = try { reader.read(0) } catch (e: Exception) {
                    throw AvatarRejected("decode-failed", "bounded decode failed: ${e.message}")
                } ?: throw AvatarRejected("decode-failed", "decoder returned no image")
                return Triple(img, w, h)
            } finally {
                reader.dispose()
            }
        } finally {
            iis.close()
        }
    }

    /** Opaque server-minted ref = first 12 hex of SHA-256 of the stored PNG (deterministic, cache-busting). */
    internal fun contentRef(png: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }.substring(0, 12)

    private fun hexPrefix(b: ByteArray): String =
        b.take(8).joinToString(" ") { "%02X".format(it) }
}

/** The tunable, single-sourced limits (documented; the route + this processor share them — no drift). */
object AvatarLimits {
    /** Hard byte ceiling (~5 MB, the PO richtwert). The route ALSO caps the read so a lying length can't pass. */
    const val MAX_BYTES: Int = 5 * 1024 * 1024
    /** Decompression-bomb cap: total decoded pixels. */
    const val MAX_PIXELS: Long = 16_000_000L
    /** Each side must be ≤ this (defence in depth with the pixel cap). */
    const val MAX_SIDE: Int = 8192
    /** The canonical square output edge. */
    const val OUT_SIZE: Int = 256
}

/**
 * The processed, safe-to-store result. `bytesIn/out`, source `format` + `srcWidth/Height`, output dims
 * and the minted `ref` feed the success diagnostic ("bytes-in/out + dims"). [png] is the ONLY thing stored.
 */
data class AvatarProcessResult(
    val png: ByteArray,
    val bytesIn: Int,
    val bytesOut: Int,
    val srcFormat: String,
    val srcWidth: Int,
    val srcHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    val ref: String,
) {
    // ByteArray in a data class: value-based equality is meaningless/misleading here → identity only.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * A fail-closed rejection. [check] names the exact gate that fired (magic-bytes / size-cap / pixel-cap /
 * decode-failed / …) for the SERVER LOG (diagnostics: "reject reason = which check"); the client only ever
 * sees a UNIFORM 400 (no parser-mapping leak).
 */
class AvatarRejected(val check: String, message: String) : Exception(message)
