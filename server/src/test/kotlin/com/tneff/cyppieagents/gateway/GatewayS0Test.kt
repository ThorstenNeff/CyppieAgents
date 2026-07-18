package com.tneff.cyppieagents.gateway

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-638 S0 — real-proof tooth for the isolated Gateway process scaffold (A2). Runs the actual [gatewayModule] in front
 * of a REAL fake hub (embedded, ephemeral port) and proves the S0 guarantees end-to-end (not a unit pass):
 *  1. an ALLOWED path (`/api/health`) is **forwarded verbatim** — the browser sees the hub's real response;
 *  2. the machine/control surface (`/api/cp/hubs`) is **refused at the EDGE (404)** and **never reaches the hub** —
 *     default-deny. Mutation: widen [GatewayAllowlist] to allow-all → `/api/cp/hubs` reaches the fake hub and returns
 *     its secret body → both control-surface assertions red.
 *  3. **same-origin SPA serving** — S0's own scope (contract R3). CYP-666: this half was accepted UNCHECKED (S0 shipped
 *     with zero static-serving and nothing caught it); [gateway_servesSpaSameOrigin_andDenySurvivesTheSpaLeg] now pins
 *     it AT the story that claims it, so "S0 is done" is true. (The full traversal + cookie-roundtrip acceptance lives
 *     in [GatewayS7StaticLegTest].)
 */
class GatewayS0Test {

    private fun startFakeHub(): Pair<io.ktor.server.engine.EmbeddedServer<*, *>, Int> {
        val hub = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/health") { call.respondText("ok") }                       // the allowed data-plane route
                get("/api/cp/hubs") { call.respondText("SECRET-CONTROL-SURFACE") }   // MUST be unreachable via the gateway
            }
        }.start(wait = false)
        val port = runBlocking { hub.engine.resolvedConnectors().first().port }
        return hub to port
    }

    @Test
    fun gateway_forwardsAllowedHealth_andRefusesControlSurfaceAtTheEdge() {
        val (hub, port) = startFakeHub()
        try {
            testApplication {
                application { gatewayModule("http://127.0.0.1:$port") }

                // (1) allowed → forwarded verbatim from the hub
                val health = client.get("/api/health")
                assertEquals(HttpStatusCode.OK, health.status, "allowed /api/health forwards")
                assertEquals("ok", health.bodyAsText(), "the browser sees the hub's real response")

                // (2) ★ default-deny → the control surface is refused AT THE EDGE (404), never reaching the hub
                val cp = client.get("/api/cp/hubs")
                assertEquals(HttpStatusCode.NotFound, cp.status, "control surface 404s at the gateway edge")
                assertNotEquals(
                    "SECRET-CONTROL-SURFACE", cp.bodyAsText(),
                    "the hub's control-surface body must NEVER traverse the gateway",
                )
            }
        } finally {
            hub.stop(0, 0)
        }
    }

    @Test
    fun gateway_servesSpaSameOrigin_andDenySurvivesTheSpaLeg() {
        val (hub, port) = startFakeHub()
        val spa = File.createTempFile("s0-spa", "").let { it.delete(); it.mkdirs(); it }
        File(spa, "index.html").writeText("S0-SPA-INDEX")
        try {
            testApplication {
                application { gatewayModule("http://127.0.0.1:$port", spaDir = spa) }

                // (3) the SPA half of S0's own scope: same-origin serving of the SPA (index + client-side deep link).
                client.get("/").let {
                    assertEquals(HttpStatusCode.OK, it.status, "GET / serves the SPA (S0's SPA-serving scope)")
                    assertEquals("S0-SPA-INDEX", it.bodyAsText(), "the SPA index is served same-origin")
                }
                assertEquals("S0-SPA-INDEX", client.get("/some/client/route").bodyAsText(), "a deep link falls back to the SPA index")

                // ★ the deny MUST survive the SPA leg — the static fallback must not turn the control-surface 404 into a
                //   200 index.html (the exact regression CYP-666's fix must not introduce).
                val cp = client.get("/api/cp/hubs")
                assertEquals(HttpStatusCode.NotFound, cp.status, "control surface stays 404 WITH the SPA leg active")
                assertTrue(cp.bodyAsText().let { it != "S0-SPA-INDEX" && it != "SECRET-CONTROL-SURFACE" }, "cp is neither the SPA nor the hub body")
            }
        } finally {
            hub.stop(0, 0); spa.deleteRecursively()
        }
    }
}
