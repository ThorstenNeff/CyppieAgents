package com.tneff.cyppieagents.gateway

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket as clientWebSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-638 S4 — **reconnect fidelity: `?since` cursor pass-through**, MEASURED (not asserted by a comment).
 *
 * S4's design line (`docs/design/CYP-638-gateway-design.md:139`) is *"`?since` pass-through + close-1008 unmasked,
 * proven with a real revoke"*. The **close-1008 half is already proven end-to-end** by
 * [GatewayS5Test.revokeWhileLive_tearsTheSocket_acrossTheGatewayHop] (a real revoke of a live PTY → 1008
 * `grant_revoked` reaches the browser UNMASKED across the hop, mutation-proven). This class pins the **other half**:
 * that the reconnect cursor `?since=<seq>` reaches the HUB verbatim across the gateway hop — the query the client
 * resends on a WS/REST reconnect to resume from its last seen event (CYP-198/204 cursor-resume, `/ws/agent`; the
 * `?since` message/event replay on the REST legs).
 *
 * The gateway forwards `call.request.uri` (path **+ query**) verbatim on BOTH legs — [GatewayServer.proxyWebSocketToHub]
 * (`target = hubWsBase + browserCall.request.uri`) and [GatewayServer.forwardToUpstream] (`val uri = call.request.uri`).
 * That is a CLAIM in a code comment; here a real reconnect drives `?since` through and a fake hub reports the cursor it
 * actually received, so the claim becomes a measurement.
 *
 * Mutation-proven: change either forward to drop the query (`request.path()` instead of `request.uri`) → the hub sees
 * `since=<none>` → red. A green here means: no reconnect story to BUILD — S4 is a tooth, this pins it.
 */
class GatewayS4CursorFidelityTest {

    private val servers = mutableListOf<io.ktor.server.engine.EmbeddedServer<*, *>>()
    @AfterTest fun tearDown() = servers.forEach { it.stop(0, 0) }

    private fun start(block: io.ktor.server.application.Application.() -> Unit): Int {
        val s = embeddedServer(Netty, port = 0, module = block).start(wait = false)
        servers += s
        return runBlocking { s.engine.resolvedConnectors().first().port }
    }

    /** Fake hub: reports back the `since` cursor it received — on the WS reconnect socket (`/ws/agent`, an allowed WS
     *  channel) as a frame, and on the REST replay leg (`/api/events`, an allowed op) in the body. `<none>` if absent. */
    private fun fakeHub(): Int = start {
        install(WebSockets)
        routing {
            webSocket("/ws/agent") {
                send(Frame.Text("SINCE=${call.request.queryParameters["since"] ?: "<none>"}"))
            }
            get("/api/events") {
                call.respondText("SINCE=${call.request.queryParameters["since"] ?: "<none>"}")
            }
        }
    }

    private fun gateway(hubPort: Int): Int = start { gatewayModule("http://127.0.0.1:$hubPort") }

    @Test
    fun wsReconnectCursor_reachesHubVerbatim_acrossTheGatewayHop() {
        val gp = gateway(fakeHub())
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        runBlocking {
            client.clientWebSocket("ws://127.0.0.1:$gp/ws/agent?since=$CURSOR") {
                val seen = withTimeout(10_000) { (incoming.receive() as Frame.Text).readText() }
                assertEquals(
                    "SINCE=$CURSOR", seen,
                    "the WS reconnect cursor ?since=$CURSOR must reach the hub verbatim across the gateway hop (got: $seen)",
                )
            }
        }
        client.close()
    }

    @Test
    fun restReplayCursor_reachesHubVerbatim_acrossTheGatewayHop() {
        val gp = gateway(fakeHub())
        val client = HttpClient(CIO)
        runBlocking {
            val body = client.get("http://127.0.0.1:$gp/api/events?since=$CURSOR").bodyAsText()
            assertEquals(
                "SINCE=$CURSOR", body,
                "the REST replay cursor ?since=$CURSOR must reach the hub verbatim across the gateway hop (got: $body)",
            )
        }
        client.close()
    }

    private companion object {
        // A distinctive value so the assertion proves VERBATIM pass-through, not mere presence of a `since` key.
        const val CURSOR = "seq-4242"
    }
}
