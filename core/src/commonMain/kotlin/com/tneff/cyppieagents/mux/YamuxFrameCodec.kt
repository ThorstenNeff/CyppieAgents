package com.tneff.cyppieagents.mux

/**
 * CYP-620 — encode/decode for the [YamuxFrame] wire (yamux spec v0, big-endian 12-byte header). Shared `:core` codec
 * (see [YamuxFrame]) → compiler-enforced across hub + client.
 *
 * **Fail-closed (no-byte-bleed / §4.8):** the [YamuxFrameDecoder] NEVER best-effort-skips. A bad version, an unknown
 * type, or a DATA `Length` beyond [maxFrameLen] throws [YamuxProtocolException] — the caller responds with
 * `GoAway(PROTOCOL_ERROR)` and tears the tunnel. This is the load-bearing correctness surface (adversarially teethed):
 * a malformed frame can never cause a payload to land in the wrong stream's socket.
 */

/** A wire-protocol violation — the caller MUST fail closed (GoAway + tunnel reset), never continue. */
class YamuxProtocolException(message: String) : Exception(message)

object YamuxFrameCodec {
    const val VERSION: Int = 0
    const val HEADER_SIZE: Int = 12

    /** Serialize [frame] to its on-the-wire bytes (12-byte header + DATA payload, if any). */
    fun encode(frame: YamuxFrame): ByteArray {
        val payloadLen = if (frame.type == YamuxType.DATA) frame.payload.size else 0
        val out = ByteArray(HEADER_SIZE + payloadLen)
        out[0] = VERSION.toByte()
        out[1] = frame.type.code.toByte()
        putU16(out, 2, frame.flags)
        putU32(out, 4, frame.streamId)
        // DATA: Length = payload size. WINDOW_UPDATE/PING/GO_AWAY: Length = the typed value.
        putU32(out, 8, if (frame.type == YamuxType.DATA) frame.payload.size.toLong() else frame.length)
        if (payloadLen > 0) frame.payload.copyInto(out, HEADER_SIZE)
        return out
    }

    internal fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    internal fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    internal fun getU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    internal fun getU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}

/**
 * A stateful, streaming decoder: frames arrive over the reassembled byte-stream (the Noise-msg↔byte-stream adapter),
 * so [feed] accepts an arbitrary chunk and returns every COMPLETE frame now available, buffering any partial tail for
 * the next [feed]. A frame boundary never straddles a lost byte (the adapter is loss-free); a violation fails closed.
 *
 * @param maxFrameLen the hard cap on a DATA payload length (M1: ≤ the Noise-msg-reassembly bound; a `Length` beyond it
 *   is a protocol error, never a huge allocation — a DoS guard).
 */
class YamuxFrameDecoder(private val maxFrameLen: Int = DEFAULT_MAX_FRAME_LEN) {
    private var buf = ByteArray(0)

    /** Append [chunk] and return every complete frame now decodable, in order. Throws [YamuxProtocolException] on a
     *  wire violation (the caller then GoAways + tears the tunnel). */
    fun feed(chunk: ByteArray): List<YamuxFrame> {
        if (chunk.isNotEmpty()) buf = if (buf.isEmpty()) chunk.copyOf() else buf + chunk
        val out = ArrayList<YamuxFrame>()
        var off = 0
        while (buf.size - off >= YamuxFrameCodec.HEADER_SIZE) {
            val version = buf[off].toInt() and 0xFF
            if (version != YamuxFrameCodec.VERSION) throw YamuxProtocolException("bad yamux version $version")
            val typeCode = buf[off + 1].toInt() and 0xFF
            val type = YamuxType.fromCode(typeCode) ?: throw YamuxProtocolException("unknown yamux type $typeCode")
            val flags = YamuxFrameCodec.getU16(buf, off + 2)
            val streamId = YamuxFrameCodec.getU32(buf, off + 4)
            val length = YamuxFrameCodec.getU32(buf, off + 8)

            if (type == YamuxType.DATA) {
                if (length > maxFrameLen) throw YamuxProtocolException("DATA length $length > maxFrameLen $maxFrameLen")
                val total = YamuxFrameCodec.HEADER_SIZE + length.toInt()
                if (buf.size - off < total) break // partial payload — wait for more
                val payload = buf.copyOfRange(off + YamuxFrameCodec.HEADER_SIZE, off + total)
                out.add(YamuxFrame(YamuxType.DATA, flags, streamId, length, payload))
                off += total
            } else {
                // WINDOW_UPDATE / PING / GO_AWAY: no payload, Length is a value.
                out.add(YamuxFrame(type, flags, streamId, length))
                off += YamuxFrameCodec.HEADER_SIZE
            }
        }
        // Keep only the undecoded tail.
        buf = if (off == 0) buf else if (off >= buf.size) ByteArray(0) else buf.copyOfRange(off, buf.size)
        return out
    }

    /** Bytes currently buffered awaiting more (a partial frame). Test-visibility / leak guard. */
    fun pending(): Int = buf.size

    companion object {
        /** Default DATA-payload cap (16 KB) — matches the adapter chunking; the deploy can retune, single-sourced. */
        const val DEFAULT_MAX_FRAME_LEN: Int = 16 * 1024
    }
}
