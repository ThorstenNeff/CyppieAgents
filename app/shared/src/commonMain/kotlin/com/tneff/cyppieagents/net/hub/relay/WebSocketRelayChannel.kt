package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close

/**
 * CYP-494 — adapts a Ktor [WebSocketSession] to a [RelayChannel] with the **LOCKED** framing (CYP-443 §4.3):
 * **1 WS binary frame = 1 Noise message, NO length prefix** — the WS frame boundary IS the message boundary.
 * [send] emits exactly one binary frame of the given bytes (verbatim, no framing added); [receive] returns
 * exactly one inbound frame's bytes. A non-binary frame or a closed channel ⇒ `null` (EOF, fail-closed → the
 * tunnel resets, the §4 truncation-guard). The relay forwards these frames opaquely; it never sees the plaintext.
 */
class WebSocketRelayChannel(private val session: WebSocketSession) : RelayChannel {

    override suspend fun send(frame: ByteArray) {
        session.send(Frame.Binary(fin = true, data = frame))
    }

    override suspend fun receive(): ByteArray? {
        val frame = session.incoming.receiveCatching().getOrNull() ?: return null // channel closed → EOF
        return (frame as? Frame.Binary)?.data // Binary → verbatim bytes; Close / other → null (fail-closed)
    }

    override suspend fun close() {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "client closed")) }
    }
}
