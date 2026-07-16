package com.tneff.cyppieagents.gateway

import com.tneff.cyppieagents.contract.ContractGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-638 S2 — real-proof tooth for the WS passthrough, **single-sourced from [ContractGenerator.WS_CHANNELS]**. Runs
 * the actual [gatewayModule] as a real server in front of a real fake WS hub, over a real WS client, and proves:
 *  1. **every** one of the 8 `WS_CHANNELS` proxies (frames both ways) — a hand-list subset would fail to connect a
 *     missing channel → red (no drift);
 *  2. the **Cookie survives the WSS upgrade** — the hub echoes back the `Cookie` it received (mutation: add `cookie` to
 *     `WS_HANDSHAKE_STRIP` → the hub sees `<none>` → red);
 *  3. `/ws/hub` (in [ContractGenerator.EXCLUDED_WS_PATHS]) has no WS route → **refused at the edge** (handshake fails).
 */
class GatewayS2Test {

    /** A fake hub with a WS echo on EVERY ContractGenerator channel: it greets with the `Cookie` it saw (so cookie
     *  survival is observable) then echoes text frames. */
    private fun startFakeWsHub(): Pair<io.ktor.server.engine.EmbeddedServer<*, *>, Int> {
        val hub = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                ContractGenerator.WS_CHANNELS.forEach { ch ->
                    webSocket(ch.path) {
                        val cookie = call.request.headers["Cookie"] ?: "<none>"
                        send(Frame.Text("HUB ${ch.path} COOKIE=$cookie"))
                        for (f in incoming) if (f is Frame.Text) send(Frame.Text("ECHO:" + f.readText()))
                    }
                }
                // A REAL /ws/hub downstream (the control-plane wire) that emits an identifiable secret — so the F4
                // refusal tooth measures ACTIVE refusal (the secret must never traverse), not mere route-absence.
                webSocket("/ws/hub") { send(Frame.Text("SECRET-HUB-WIRE")); for (f in incoming) { /* drain */ } }
            }
        }.start(wait = false)
        val port = runBlocking { hub.engine.resolvedConnectors().first().port }
        return hub to port
    }

    private fun startGateway(hubPort: Int): Pair<io.ktor.server.engine.EmbeddedServer<*, *>, Int> {
        val gw = embeddedServer(Netty, port = 0) { gatewayModule("http://127.0.0.1:$hubPort") }.start(wait = false)
        val port = runBlocking { gw.engine.resolvedConnectors().first().port }
        return gw to port
    }

    @Test
    fun everyWsChannelProxies_andCookieSurvivesTheUpgrade() {
        val (hub, hp) = startFakeWsHub()
        val (gw, gp) = startGateway(hp)
        val wsClient = HttpClient(CIO) { install(ClientWebSockets) }
        try {
            runBlocking {
                for (ch in ContractGenerator.WS_CHANNELS) {
                    wsClient.webSocket("ws://127.0.0.1:$gp${ch.path}?token=x&since=0", request = {
                        header("Cookie", "ory_kratos_session=sekret")
                    }) {
                        val hello = (incoming.receive() as Frame.Text).readText()
                        assertTrue(hello.contains("ory_kratos_session=sekret"), "${ch.path}: Cookie survived the WSS upgrade (got '$hello')")
                        send(Frame.Text("ping"))
                        val echo = (incoming.receive() as Frame.Text).readText()
                        assertEquals("ECHO:ping", echo, "${ch.path}: frames proxy both ways")
                    }
                }
            }
        } finally {
            wsClient.close(); gw.stop(0, 0); hub.stop(0, 0)
        }
    }

    @Test
    fun wsHub_isActivelyRefusedAtTheEdge_andTheHubWireSecretNeverLeaks() {
        val (hub, hp) = startFakeWsHub() // has a real /ws/hub secret downstream
        val (gw, gp) = startGateway(hp)
        val wsClient = HttpClient(CIO) { install(ClientWebSockets) }
        try {
            // (a) CONCRETE refusal — the upgrade to the excluded socket gets 404 AT THE EDGE. A broken gateway / wrong
            //     port / hub-down would NOT yield exactly 404, so this can't pass by environment breakage (F4 fix).
            assertEquals(404, rawWsUpgradeStatus(gp, "/ws/hub"), "/ws/hub upgrade → 404 at the edge (active refusal)")
            // positive control: an allowed channel DOES upgrade (101) — proves the probe reads real status, so the 404
            // above measures the refusal, not the absence of a route.
            assertEquals(101, rawWsUpgradeStatus(gp, "/ws/comm"), "an allowed channel upgrades (101)")
            // (b) DISCRIMINATOR — even attempting a real WS, the /ws/hub hub-wire secret (a live downstream on the fake
            //     hub) must NEVER traverse the gateway. If the deny broke, this is what would come through.
            var received: String? = null
            runCatching {
                runBlocking {
                    kotlinx.coroutines.withTimeout(3000) {
                        wsClient.webSocket("ws://127.0.0.1:$gp/ws/hub") {
                            received = (incoming.receive() as? Frame.Text)?.readText()
                        }
                    }
                }
            }
            assertNotEquals("SECRET-HUB-WIRE", received, "the /ws/hub hub-wire secret must never leak through the gateway")
        } finally {
            wsClient.close(); gw.stop(0, 0); hub.stop(0, 0)
        }
    }

    /** Send a raw WS-upgrade request and return the HTTP status of the FIRST response line (101 upgrade / 404 refused).
     *  Concrete status — unlike `catch(_: Exception)`, a broken env yields a connect error, not a spurious pass. */
    private fun rawWsUpgradeStatus(port: Int, path: String): Int {
        java.net.Socket("127.0.0.1", port).use { s ->
            val req = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            val statusLine = s.getInputStream().bufferedReader().readLine() ?: ""
            return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }
    }
}
