package com.tneff.cyppieagents.net.hub.noise

import kotlinx.coroutines.channels.Channel

/**
 * CYP-443 Slice 1 test double — an in-memory [RelayChannel] duplex pair. Each side's [send] lands in the other
 * side's [receive] queue (ordered, reliable — the properties the real relay WS gives). Closing a side makes the
 * peer's [receive] return `null` (EOF). Lets the Noise handshake + tunnel run with no network.
 */
class InMemoryRelayChannel(
    private val outbound: Channel<ByteArray>,
    private val inbound: Channel<ByteArray>,
) : RelayChannel {
    override suspend fun send(frame: ByteArray) {
        outbound.send(frame.copyOf())
    }

    override suspend fun receive(): ByteArray? = inbound.receiveCatching().getOrNull()

    override suspend fun close() {
        outbound.close()
    }

    companion object {
        /** A connected pair (clientSide, hubSide): what one sends, the other receives. */
        fun pair(): Pair<InMemoryRelayChannel, InMemoryRelayChannel> {
            val a2b = Channel<ByteArray>(Channel.UNLIMITED)
            val b2a = Channel<ByteArray>(Channel.UNLIMITED)
            return InMemoryRelayChannel(outbound = a2b, inbound = b2a) to
                InMemoryRelayChannel(outbound = b2a, inbound = a2b)
        }
    }
}
