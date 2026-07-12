package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.net.hub.noise.RelayChannel

/**
 * CYP-494 — opens the relay WebSocket to a resolved rendezvous and adapts it to a message-preserving
 * [RelayChannel]. A seam so the [RendezvousRelayDialer] is testable with an in-memory channel; the live impl
 * ([KtorWsRelayConnector]) uses the Ktor client WebSocket.
 */
fun interface RelayWsConnector {
    /** Open the relay WS at [relayUrl], joining opaque [rendezvousId] as the client role. Throws on connect failure. */
    suspend fun open(relayUrl: String, rendezvousId: String): RelayChannel
}
