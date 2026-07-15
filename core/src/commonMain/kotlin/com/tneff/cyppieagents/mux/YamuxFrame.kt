package com.tneff.cyppieagents.mux

/**
 * CYP-620 — the **yamux (spec v0) frame model**, the shared wire contract for the multiplexed remote transport.
 *
 * This codec lives in `:core` (common) ON PURPOSE: the hub (JVM) and the client (KMP) compile against ONE impl, so the
 * wire contract is **compiler-enforced**, not merely conformance-tested — a bilateral divergence is structurally
 * impossible. Wire = the yamux specification v0 (authoritative: hashicorp/yamux). There is no off-the-shelf JVM+KMP
 * yamux library, so we implement the proven spec here; `YamuxFrameCodecTest` validates it against the spec's byte
 * layout + hashicorp/yamux conformance vectors. **All multi-byte integers are big-endian.**
 *
 * Header (12 bytes): `[ Version:u8=0 ][ Type:u8 ][ Flags:u16 ][ StreamID:u32 ][ Length:u32 ]` then `Length` payload
 * bytes — but the payload bytes are present ONLY for [YamuxType.DATA]. For WINDOW_UPDATE / PING / GO_AWAY the `Length`
 * field is a **value** (window delta / opaque ping / error code), NOT a byte count, and no payload follows.
 */

/** yamux message types (the Type byte). */
enum class YamuxType(val code: Int) {
    /** `Length` = payload length; the payload follows the header. Carries a stream's opaque bytes. */
    DATA(0),
    /** `Length` = window **DELTA** (a flow-control credit grant); no payload. */
    WINDOW_UPDATE(1),
    /** `Length` = opaque ping value; `SYN` = ping, `ACK` = pong; no payload. Keepalive / RTT. */
    PING(2),
    /** `Length` = error code ([YamuxGoAway]); no payload. Session teardown. */
    GO_AWAY(3),
    ;

    companion object {
        /** The type for [code], or `null` if unknown (→ the decoder fails closed: a protocol error, never a skip). */
        fun fromCode(code: Int): YamuxType? = entries.firstOrNull { it.code == code }
    }
}

/** yamux frame flags — a `u16` bitset (a frame may carry several). */
object YamuxFlags {
    const val NONE: Int = 0
    const val SYN: Int = 0x1 // open a stream / begin a ping
    const val ACK: Int = 0x2 // accept a stream / pong
    const val FIN: Int = 0x4 // graceful half-close → CYP-609 per-stream `shutdownOutput` (FIN)
    const val RST: Int = 0x8 // abrupt abort → CYP-609 per-stream RST (truncation guard, per stream)

    fun has(flags: Int, flag: Int): Boolean = (flags and flag) != 0
}

/** yamux GoAway error codes (the `Length` field on a [YamuxType.GO_AWAY] frame). */
object YamuxGoAway {
    const val NORMAL: Int = 0
    const val PROTOCOL_ERROR: Int = 1
    const val INTERNAL_ERROR: Int = 2
}

/**
 * One yamux frame. [streamId] and [length] are `u32` held in [Long] (unsigned-safe: a value > 2^31 would be a negative
 * [Int]). [payload] is non-empty ONLY for [YamuxType.DATA], and then `payload.size == length`; for the other types
 * [length] is the typed value (window delta / ping id / goaway code) and [payload] is empty.
 */
class YamuxFrame(
    val type: YamuxType,
    val flags: Int,
    val streamId: Long,
    val length: Long,
    val payload: ByteArray = EMPTY,
) {
    init {
        require(streamId in 0..U32_MAX) { "streamId out of u32 range: $streamId" }
        require(length in 0..U32_MAX) { "length out of u32 range: $length" }
        require(type != YamuxType.DATA || payload.size.toLong() == length) {
            "DATA payload size (${payload.size}) must equal Length ($length)"
        }
        require(type == YamuxType.DATA || payload.isEmpty()) { "$type carries no payload (Length is a value)" }
    }

    /** SYN — opens a stream (DATA/WINDOW_UPDATE) or begins a ping. */
    val isSyn: Boolean get() = YamuxFlags.has(flags, YamuxFlags.SYN)
    val isAck: Boolean get() = YamuxFlags.has(flags, YamuxFlags.ACK)
    val isFin: Boolean get() = YamuxFlags.has(flags, YamuxFlags.FIN)
    val isRst: Boolean get() = YamuxFlags.has(flags, YamuxFlags.RST)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YamuxFrame) return false
        return type == other.type && flags == other.flags && streamId == other.streamId &&
            length == other.length && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = type.hashCode()
        h = 31 * h + flags
        h = 31 * h + streamId.hashCode()
        h = 31 * h + length.hashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    override fun toString(): String =
        "YamuxFrame($type flags=0x${flags.toString(16)} stream=$streamId length=$length payload=${payload.size}B)"

    companion object {
        val EMPTY: ByteArray = ByteArray(0)
        const val U32_MAX: Long = 0xFFFFFFFFL

        /** A DATA frame carrying [bytes] on [streamId] (optionally with [flags], e.g. `SYN` to open). */
        fun data(streamId: Long, bytes: ByteArray, flags: Int = YamuxFlags.NONE): YamuxFrame =
            YamuxFrame(YamuxType.DATA, flags, streamId, bytes.size.toLong(), bytes)

        /** A WINDOW_UPDATE granting [delta] credit on [streamId] (optionally `SYN`/`ACK`). */
        fun windowUpdate(streamId: Long, delta: Long, flags: Int = YamuxFlags.NONE): YamuxFrame =
            YamuxFrame(YamuxType.WINDOW_UPDATE, flags, streamId, delta)

        /** A PING (`SYN` + [opaque]) or PONG (`ACK` + [opaque]). Ping/pong ride stream 0. */
        fun ping(opaque: Long, flags: Int): YamuxFrame = YamuxFrame(YamuxType.PING, flags, 0, opaque)

        /** A GO_AWAY carrying [code] ([YamuxGoAway]). Rides stream 0. */
        fun goAway(code: Int): YamuxFrame = YamuxFrame(YamuxType.GO_AWAY, YamuxFlags.NONE, 0, code.toLong())
    }
}
