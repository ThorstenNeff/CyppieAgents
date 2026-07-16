package com.tneff.cyppieagents.gateway

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-638 F5 — path-traversal must not smuggle a request onto the control plane. A **measured** bypass:
 * `/api/agents/..%2fcp%2fchallenge` decodes to `/api/agents/../cp/challenge`, which (before the fix) neither started
 * with `/api/cp/` nor failed to match `/api/agents/{id}`, so the gateway forwarded it and the hub's own `..`
 * normalization landed it on `/api/cp/challenge`. The fix resolves dot-segments on the decoded path BEFORE the deny.
 *
 * Probed over a **raw socket** on purpose: a normal HTTP client normalizes `..` in the URL itself and would HIDE the
 * bug. The fake hub answers `200` to anything, so a *forwarded* traversal is observable as `200` while a denial is a
 * `404` at the gateway edge. Positive control (mandatory): a legit `/api/agents/x` stays `200` — the deny must not
 * over-block the data plane. Mutation: drop the normalize step → both traversals forward → `200` → red.
 */
class GatewayF5PathTraversalTest {

    private val servers = mutableListOf<io.ktor.server.engine.EmbeddedServer<*, *>>()
    @AfterTest fun tearDown() = servers.forEach { it.stop(0, 0) }

    private fun echoHubPort(): Int {
        val hub = embeddedServer(Netty, port = 0) {
            routing { route("{...}") { handle { call.respondText("HUB") } } } // 200 to any path → a forward is observable
        }.start(wait = false)
        servers += hub
        return runBlocking { hub.engine.resolvedConnectors().first().port }
    }

    private fun gatewayPort(hubPort: Int): Int {
        val gw = embeddedServer(Netty, port = 0) { gatewayModule("http://127.0.0.1:$hubPort") }.start(wait = false)
        servers += gw
        return runBlocking { gw.engine.resolvedConnectors().first().port }
    }

    /** Raw-socket GET: send the request line VERBATIM (no client-side `..` normalization) and read the status. */
    private fun rawGetStatus(port: Int, rawPath: String): Int {
        java.net.Socket("127.0.0.1", port).use { s ->
            val req = "GET $rawPath HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n"
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            val statusLine = s.getInputStream().bufferedReader().readLine() ?: ""
            return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    @Test
    fun encodedDotDotOntoControlPlane_isDeniedAtEdge_withoutOverBlockingTheDataPlane() {
        val gp = gatewayPort(echoHubPort())
        // ★ the bypass: encoded `..` that resolves onto the control plane → MUST be 404 at the edge (was 200)
        assertEquals(404, rawGetStatus(gp, "/api/agents/..%2fcp%2fchallenge"), "single-.. traversal onto /api/cp must 404 at the edge")
        assertEquals(404, rawGetStatus(gp, "/api/agents/..%2f..%2fcp%2fchallenge"), "double-.. traversal must 404 at the edge")
        // ★ positive control (not optional): a legit data-plane path is NOT over-blocked
        assertEquals(200, rawGetStatus(gp, "/api/agents/x"), "a legit data-plane path still forwards (no over-block)")
    }
}
