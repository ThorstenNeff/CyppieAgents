package com.tneff.cyppieagents.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * CYP-458 (S2) — the **outbound-only** relay dial seam (RR5): opens a connection **TO** the relay and returns the L0
 * frame channel. It NEVER binds a listener for the tunnel — an inbound/wildcard listener would be exactly the
 * exposition RR5 forbids. Tests inject a fake dialer; the production impl is [WebSocketRelayDialer].
 */
interface RelayDialer {
    suspend fun dial(relayUrl: String): ServerRelayChannel
}

/**
 * The production [RelayDialer]: dials the relay WebSocket **outbound** through a Ktor [HttpClient] (WebSockets
 * plugin) and registers under an **opaque** [rendezvousId] (RR4 — the relay sees only the id + ciphertext +
 * sizes/timing, never the hub identity or any payload). The exact registration scheme is **provisional** (finalised
 * with the relay server, which does not exist yet); it is carried here as an opaque header.
 */
class WebSocketRelayDialer(
    private val client: HttpClient,
    private val rendezvousId: String,
) : RelayDialer {
    override suspend fun dial(relayUrl: String): ServerRelayChannel {
        val session = client.webSocketSession(relayUrl) { header(RENDEZVOUS_HEADER, rendezvousId) }
        return RelayChannelOverWebSocket(session)
    }

    private companion object {
        const val RENDEZVOUS_HEADER = "X-Cyppie-Rendezvous"
    }
}

/**
 * CYP-458 (S2) — the **outbound-only reverse-tunnel** connector (impl of [RelayConnector]). On [start] — **only** when
 * the [RemoteTransportConfig] is enabled (INERT / local-only otherwise, parity with the `CYPPIE_MASTER_KEY`-gated CP
 * wiring) — it DIALS the relay via the [RelayDialer], runs the [ServerNoiseTerminator] (NK responder on the hub
 * `dhKey` static, CYP-457) over the L0 channel, and hands the terminated L2 tunnel to [tunnelHandler] — in production
 * `LoopbackBridge.bridge` (present L2 to the EXISTING Ktor routes over 127.0.0.1).
 *
 * **AC1 (RR5):** it only ever DIALS — there is no listener for the tunnel. **§5:** one L2 per tunnel (this MVP dials
 * one; multi-tunnel accept lands with the relay protocol). **Fail-closed:** a handshake failure closes the L0 channel.
 * INERT until CYP-459 boot-wiring flips [RemoteTransportConfig.enabled] on an explicit Phase-2-Remote-GO.
 */
class NoiseRelayConnector(
    private val config: RemoteTransportConfig,
    private val dialer: RelayDialer,
    private val terminator: ServerNoiseTerminator,
    private val tunnelHandler: suspend (ServerNoiseTunnel) -> Unit,
    private val scope: CoroutineScope,
) : RelayConnector {
    private var job: Job? = null

    override suspend fun start() {
        val url = config.relayUrl
        if (!config.enabled || url == null) return // INERT / local-only: NO outbound dial, current behaviour unchanged
        job = scope.launch {
            val relay = dialer.dial(url) // outbound-only
            val tunnel = try {
                terminator.terminate(relay) // NK responder handshake over L0
            } catch (e: Exception) {
                relay.close() // fail-closed: a failed handshake tears the L0 channel down
                throw e
            }
            tunnelHandler(tunnel) // → LoopbackBridge.bridge in production
        }
    }

    override suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }
}
