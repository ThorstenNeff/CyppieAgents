package com.tneff.cyppieagents.transport.mux

import com.tneff.cyppieagents.mux.YamuxFrame
import com.tneff.cyppieagents.mux.YamuxFrameCodec
import com.tneff.cyppieagents.mux.YamuxFrameDecoder
import com.tneff.cyppieagents.mux.YamuxProtocolException
import com.tneff.cyppieagents.transport.NOISE_MAX_FRAME
import com.tneff.cyppieagents.transport.ServerNoiseTunnel

/**
 * CYP-620 Increment 3 (adapter) — the **Noise-message ↔ yamux-frame boundary** (invariant M1). The `:core`
 * [YamuxFrameCodec] speaks a self-delimiting frame stream; the [ServerNoiseTunnel] speaks discrete AEAD messages
 * (each ≤ [NOISE_MAX_FRAME] = 65535 B on the wire). This adapter is the thin, load-bearing seam between the two, and
 * the sole place invariant **M1 (§4.8.7 — no yamux frame may exceed one Noise message)** is enforced.
 *
 * **Why a shared `:core` codec but a per-side adapter (§4.1):** the frame parse/serialize + window math is pure logic
 * and lives in `:core` (compiler-identical on hub + client). Only the *wiring* to the platform transport is per-side;
 * on the hub that transport is the [ServerNoiseTunnel]. The mirror adapter on the client wraps its own `NoiseTunnel`.
 *
 * **M1, fail-closed twice over:**
 *  - at construction — the largest frame this link will ever emit is `HEADER_SIZE + maxFrameLen`; if that exceeds the
 *    Noise-message cap the link refuses to exist (a misconfiguration can never produce an un-sendable frame);
 *  - on [write] — a frame handed in from above whose *encoded* size still exceeds the cap (e.g. a caller that built a
 *    DATA frame larger than [maxFrameLen]) is rejected with a [YamuxProtocolException], never silently truncated.
 *
 * The session above ([maxFrameLen]-chunks its per-stream reads), so in normal operation M1 holds by construction; the
 * [write] guard is the belt to the session's braces.
 *
 * **Backpressure pass-through (§4.8.7):** [write] delegates straight to [ServerNoiseTunnel.send], which suspends while
 * the underlying relay-TCP is backed up. That suspension propagates up to the caller's per-stream pump — so the yamux
 * send-window ↔ Noise-socket ↔ relay-TCP chain throttles loss-free, with no buffer of its own here. The adapter adds
 * **no** queue: exactly one Noise message per frame, and no reordering (the tunnel is a single ordered channel).
 *
 * **No-byte-bleed / fail-closed on read:** [read] feeds each inbound Noise message to a single stateful
 * [YamuxFrameDecoder]. A partial frame split across two messages is reassembled; a message carrying several frames
 * yields several. A malformed frame (bad version / unknown type / a DATA `Length` beyond [maxFrameLen]) throws out of
 * the decoder — the caller fails the whole tunnel closed (GoAway + reset), never a best-effort skip.
 */
class YamuxFrameLink(
    private val tunnel: ServerNoiseTunnel,
    /** DATA-payload cap; also the decoder's hard `Length` bound. Default = the `:core` decoder default (16 KB). */
    val maxFrameLen: Int = YamuxFrameDecoder.DEFAULT_MAX_FRAME_LEN,
) {
    init {
        // M1 at construction: the biggest frame we can emit (header + a full-size DATA payload) MUST fit one Noise msg.
        require(maxFrameLen >= 0) { "maxFrameLen must be non-negative, was $maxFrameLen" }
        require(YamuxFrameCodec.HEADER_SIZE + maxFrameLen <= NOISE_MAX_FRAME) {
            "M1 violated: max yamux frame ${YamuxFrameCodec.HEADER_SIZE + maxFrameLen} B > Noise-message cap $NOISE_MAX_FRAME B"
        }
    }

    private val decoder = YamuxFrameDecoder(maxFrameLen)

    /**
     * Read the next inbound Noise message and decode it to zero-or-more yamux frames, or `null` when the tunnel
     * closed ([ServerNoiseTunnel.receive] returned `null`). Throws [YamuxProtocolException] on a malformed frame
     * (the caller tears the tunnel closed). An empty list is possible (a message that is only a partial frame prefix).
     */
    suspend fun read(): List<YamuxFrame>? {
        val msg = tunnel.receive() ?: return null
        return decoder.feed(msg)
    }

    /**
     * Encode [frame] and send it as exactly one Noise message (M1-guarded). Suspends while the tunnel/relay-TCP is
     * backed up (backpressure pass-through). Fail-closed if the encoded frame exceeds the Noise-message cap.
     */
    suspend fun write(frame: YamuxFrame) {
        val bytes = YamuxFrameCodec.encode(frame)
        if (bytes.size > NOISE_MAX_FRAME) {
            throw YamuxProtocolException("M1: encoded frame ${bytes.size} B > Noise-message cap $NOISE_MAX_FRAME B")
        }
        tunnel.send(bytes)
    }

    suspend fun close() = tunnel.close()

    /** Bytes buffered inside the decoder awaiting more of a split frame (0 in steady state). Test/observability. */
    fun pendingIn(): Int = decoder.pending()
}
