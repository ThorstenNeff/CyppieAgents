package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.relay.RENDEZVOUS_HEADER
import com.tneff.cyppieagents.relay.ROLE_HEADER
import com.tneff.cyppieagents.relay.relayModule
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * CYP-509 — the production hub [WebSocketRelayDialer] (role=hub) interoperates end-to-end with the merged CYP-506
 * relay: it registers under its opaque rendezvous id, the relay pairs it with a role=client peer, and an opaque frame
 * forwards verbatim. This proves the two ends I own (hub dial + relay) speak the SAME finalized wire scheme
 * (`X-Cyppie-Rendezvous` + `X-Cyppie-Role`, single-sourced) — and, because the CYP-506 relay fail-closes a dial
 * missing either header, that the dialer's `role=hub` is load-bearing (the old header-only dial would never pair).
 */
class Cyp509HubDialInteropTest {

    @Test
    fun hubDialer_roleHub_pairsWithRelay_andForwardsToClient() = testApplication {
        application { relayModule() }
        val ws = createClient { install(ClientWebSockets) }
        coroutineScope {
            val clientGot = CompletableDeferred<ByteArray>()
            val clientJob = launch {
                ws.webSocket("/relay", request = { header(RENDEZVOUS_HEADER, "rzv1"); header(ROLE_HEADER, "client") }) {
                    val f = incoming.receive()
                    clientGot.complete((f as Frame.Binary).readBytes())
                }
            }
            // the HUB end via the PRODUCTION dialer — it stamps role=hub itself.
            val channel = WebSocketRelayDialer(ws, rendezvousId = "rzv1").dial("/relay")
            channel.send(byteArrayOf(4, 2)) // hub → relay → client
            assertContentEquals(byteArrayOf(4, 2), withTimeout(5_000) { clientGot.await() }, "the role=hub dialer pairs with the relay and forwards to the client")
            channel.close(); clientJob.cancel()
        }
    }
}
