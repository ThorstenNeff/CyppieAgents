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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-638 S0 — real-proof tooth for the isolated Gateway process scaffold (A2). Runs the actual [gatewayModule] in front
 * of a REAL fake hub (embedded, ephemeral port) and proves the two S0 guarantees end-to-end (not a unit pass):
 *  1. an ALLOWED path (`/api/health`) is **forwarded verbatim** — the browser sees the hub's real response;
 *  2. the machine/control surface (`/api/cp/hubs`) is **refused at the EDGE (404)** and **never reaches the hub** —
 *     default-deny. Mutation: widen [GatewayAllowlist] to allow-all → `/api/cp/hubs` reaches the fake hub and returns
 *     its secret body → both control-surface assertions red.
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
}
