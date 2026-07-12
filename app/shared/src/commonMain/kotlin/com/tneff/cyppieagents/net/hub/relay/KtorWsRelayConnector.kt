package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header

/**
 * CYP-494 — the live [RelayWsConnector]: opens the relay WS via the Ktor client, joining the opaque rendezvous
 * as the **client** role with the CYP-506/509 dial headers (`X-Cyppie-Rendezvous` + `X-Cyppie-Role: client`), and
 * wraps the session in a [WebSocketRelayChannel] (message-preserving framing). The relay is an **untrusted** dumb
 * pipe — no auth here; authenticity is the end-to-end Noise_NK + RR3 (CYP-501 §2). [client] must have the Ktor
 * `WebSockets` plugin installed.
 *
 * Remote mode is jvm-only in the MVP ([com.tneff.cyppieagents.connect.remoteHubEnabled]); this compiles on every
 * target (Ktor client WS is multiplatform) but is only invoked on jvm — where WS request headers are settable
 * (on a browser they are not; if remote ever ships to web, the dial identifiers move to the query string).
 */
class KtorWsRelayConnector(private val client: HttpClient) : RelayWsConnector {

    override suspend fun open(relayUrl: String, rendezvousId: String): RelayChannel {
        val session = client.webSocketSession(urlString = relayUrl) {
            header(RELAY_HEADER_RENDEZVOUS, rendezvousId)
            header(RELAY_HEADER_ROLE, RELAY_ROLE_CLIENT)
        }
        return WebSocketRelayChannel(session)
    }

    companion object {
        const val RELAY_HEADER_RENDEZVOUS = "X-Cyppie-Rendezvous"
        const val RELAY_HEADER_ROLE = "X-Cyppie-Role"
        const val RELAY_ROLE_CLIENT = "client"
    }
}
