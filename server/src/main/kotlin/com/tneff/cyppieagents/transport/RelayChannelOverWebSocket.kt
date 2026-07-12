package com.tneff.cyppieagents.transport

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * CYP-458 (S2) — L0 adapter: a Ktor client [WebSocketSession] (the outbound relay dial) presented as a
 * [ServerRelayChannel]. Each relay WS **binary** frame is exactly one Noise message (the LOCKED CYP-443 framing:
 * message-preserving WS, **no** length prefix, §4.3). Inbound frames are buffered in a **bounded** channel
 * (≤ [maxInFlight], §3) so a fast/hostile relay applies backpressure and can never OOM the hub. `receive()` returns
 * `null` when the relay closed (fail-closed). One WS session = one L0 channel = one tunnel (no mux, §5).
 */
class RelayChannelOverWebSocket(
    private val session: WebSocketSession,
    maxInFlight: Int = 8,
) : ServerRelayChannel {
    private val inbound = Channel<ByteArray>(maxInFlight)

    // Drain the WS into the bounded [inbound]; `send` on a full channel SUSPENDS → the relay is backpressured (§3).
    private val pump = session.launch {
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Binary) inbound.send(frame.readBytes())
                // non-binary frames (ping/pong/close) are not tunnel data — ignored; a close ends the loop.
            }
        } catch (_: Exception) {
            // relay/session error — fall through and close the inbound (fail-closed: receive() then yields null).
        } finally {
            inbound.close()
        }
    }

    override suspend fun send(frame: ByteArray) {
        session.send(Frame.Binary(true, frame))
    }

    override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()

    override suspend fun close() {
        pump.cancel()
        runCatching { session.close() }
        inbound.close()
    }
}
