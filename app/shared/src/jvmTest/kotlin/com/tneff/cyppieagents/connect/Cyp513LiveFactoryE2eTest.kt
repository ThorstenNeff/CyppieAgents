package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.controlplane.RendezvousFailure
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import com.tneff.cyppieagents.net.hub.operator.ChannelBinding
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-513 e2e — the LIVE [liveRemoteConnectComponentsFactory] composition, driven over a real CP. The composed
 * session's dialer IS the swapped-in `RendezvousRelayDialer(HttpRendezvousResolver, KtorWsRelayConnector)`: when
 * the CP resolves `NOT_REGISTERED`, that flows resolver → `RelayDialException(HubOffline)` → the session surfaces
 * **`HubOffline`** (the distinct cause), not a generic RelayUnreachable and never a fake CONNECTED. Uses real time
 * (`runBlocking`) because the composition does real HTTP.
 */
class Cyp513LiveFactoryE2eTest {

    private val hub = HubDescriptor("hub-a", "host-a", online = true, defaultPort = 8787, lastSeen = 1L)
    private val fakeCb = ChannelBinding { _, _ -> "cb" }

    @Test
    fun liveFactory_resolveNotRegistered_surfacesHubOffline_viaComposedDialerSwap() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/cp/rendezvous/{hubId}") {
                    call.respondText(
                        CommJson.encodeToString(
                            RendezvousResolveResponse.serializer(),
                            RendezvousResolveResponse(failure = RendezvousFailure.NOT_REGISTERED),
                        ),
                    )
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val cpClient = HttpClient(CIO)
            val wsClient = HttpClient(CIO) { install(WebSockets) }
            try {
                val factory = liveRemoteConnectComponentsFactory(
                    cpBaseUrl = "http://127.0.0.1:$port",
                    cpHttpClient = cpClient,
                    operatorToken = { "sess-op" },
                    relayWsClient = wsClient,
                    channelBinding = fakeCb,
                )
                val comps = factory.create(hub, this)
                comps.session.start()
                val failure = withTimeout(15_000) { comps.session.state.first { it.failure != null }.failure }
                assertEquals(RemoteFailure.HubOffline, failure, "NOT_REGISTERED → HubOffline through the composed dialer-swap")
                comps.session.close()
            } finally {
                cpClient.close()
                wsClient.close()
            }
        } finally {
            server.stop()
        }
    }
}
