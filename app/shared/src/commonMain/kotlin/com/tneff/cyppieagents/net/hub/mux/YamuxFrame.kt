package com.tneff.cyppieagents.net.hub.mux

/**
 * CYP-620 (client-side mux) — the **pinned yamux-spec-v0 contract**, mirrored on the client so the wiring +
 * orchestration + teeth can build now. The authoritative frame **codec** (byte-level encode/decode) is the shared
 * `:core` yamux codec that BOTH client and server consume (PO1-pending) — this file is the CLIENT's view of the
 * pinned constants + frame model, to be **reconciled/deduped against `:core`** when it lands (no divergence: the
 * values here are the ratified contract, verified against hashicorp/yamux via Context7).
 *
 * Frame header (12 bytes, big-endian — hashicorp/yamux protoVersion 0):
 * `[Version:u8][Type:u8][Flags:u16][StreamID:u32][Length:u32]`, then `Length` payload bytes for a DATA frame.
 */

/** yamux message type (spec-v0): the wire byte at header[1]. */
enum class FrameType(val wire: Int) {
    DATA(0),          // stream payload (raw HTTP/WS bytes; routes unchanged)
    WINDOW_UPDATE(1), // flow-control: `length` = window delta to credit the peer
    PING(2),          // keepalive (SYN=request/ACK=reply), `length` = opaque token
    GO_AWAY(3);       // session teardown, `length` = GoAway code (0 normal / 1 proto-err / 2 internal-err)

    companion object {
        fun fromWire(wire: Int): FrameType? = entries.firstOrNull { it.wire == wire }
    }
}

/** yamux frame flags (bitmask at header[2..4]). SYN/ACK open+confirm a stream; FIN half-closes; RST hard-resets. */
object YamuxFlags {
    const val SYN: Int = 1 // 1 << 0 — open a stream (or ping request)
    const val ACK: Int = 2 // 1 << 1 — accept a stream (or ping reply)
    const val FIN: Int = 4 // 1 << 2 — half-close (no more data this direction)
    const val RST: Int = 8 // 1 << 3 — hard-reset the stream
}

/**
 * CYP-620 (pinned) — the 4 **stream classes** carried in the **first SYN-payload byte**, so the transport can give
 * each stream its lane/QoS priority over the one shared tunnel (the CYP-616 lane concept, now per-stream). The class
 * is advisory-for-priority; the stream then carries raw HTTP/WS bytes regardless. Pinned wire values 0..3.
 */
enum class StreamClass(val wire: Int) {
    CONTROL(0),      // control / lifecycle (stop/start/restart/reprovision) — the CYP-616 reserved-priority class
    AGENT_WS(1),     // a per-agent `/ws/agent` socket
    SINGLETON_WS(2), // a shared singleton WS (comm/events/acl/busy/token-usage/terminal-state/lifecycle-status/capacity)
    REST(3);         // any other (non-lifecycle) REST

    companion object {
        fun fromWire(wire: Int): StreamClass? = entries.firstOrNull { it.wire == wire }
    }
}

/** CYP-620 pinned protocol constants (yamux-spec-v0; verified against hashicorp/yamux). Never re-literal elsewhere. */
object YamuxProtocol {
    const val VERSION: Int = 0
    const val HEADER_SIZE: Int = 12
    /** Per-stream flow-control window initial size (256 KiB) — the peer may send this much before a WindowUpdate. */
    const val INITIAL_STREAM_WINDOW: Int = 256 * 1024
}

/**
 * One decoded yamux frame: the 12-byte-header fields + the payload. For a `DATA` frame `length == payload.size`
 * (the raw HTTP/WS bytes); for `WINDOW_UPDATE` `length` is the window delta (no payload); for `GO_AWAY` `length`
 * is the code (no payload); a `SYN`-flagged stream-open carries the [StreamClass] as its first payload byte.
 */
data class YamuxFrame(
    val type: FrameType,
    val flags: Int,      // bitmask of [YamuxFlags]
    val streamId: Long,  // u32 (0 = session-level control: Ping / GoAway)
    val length: Long,    // u32 — DATA: payload len · WINDOW_UPDATE: delta · GO_AWAY: code · PING: token
    val payload: ByteArray = ByteArray(0),
) {
    fun hasFlag(flag: Int): Boolean = (flags and flag) != 0

    // ByteArray needs structural equals/hashCode for value semantics (deterministic tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YamuxFrame) return false
        return type == other.type && flags == other.flags && streamId == other.streamId &&
            length == other.length && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + flags
        result = 31 * result + streamId.hashCode()
        result = 31 * result + length.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * CYP-620 — the frame **codec** seam the client consumes. The IMPL is the shared `:core` yamux codec (PO1-pending);
 * the client wiring/session/teeth build against THIS interface and pull the `:core` impl when confirmed (so the exact
 * byte encode/decode is single-sourced client↔server — no divergence). **Shape provisional** until the `:core` codec
 * owner ratifies it (per the coordinator) — reconcile before final wiring.
 */
interface YamuxFrameCodec {
    /** Serialize a frame to its 12B-header + payload wire bytes. */
    fun encode(frame: YamuxFrame): ByteArray

    /**
     * Read exactly one frame from [read] (a suspend byte-reader that fills a buffer or returns -1 at clean EOF),
     * or `null` on clean end-of-stream. Throws on a malformed/oversized frame (fail-closed — the session GoAways).
     */
    suspend fun decode(read: suspend (ByteArray) -> Int): YamuxFrame?
}
