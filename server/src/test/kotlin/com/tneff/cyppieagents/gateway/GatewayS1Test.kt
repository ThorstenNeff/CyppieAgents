package com.tneff.cyppieagents.gateway

import com.tneff.cyppieagents.contract.RestContract
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-638 S1 — real-proof tooth for the REST data-plane allowlist, **single-sourced from [RestContract.REST_OPS]**.
 *
 * The no-drift proof drives the ACTUAL `REST_OPS` list through the gateway: **every** data-plane op must forward and
 * **every** control op under the `/api/cp` prefix must 404 at the edge. If the allowlist were hand-copied (a subset),
 * some data-plane op would 404 → red; if the `/api/cp` exclusion were dropped (naively allowing all of `REST_OPS`,
 * which mixes the control plane in), a control op would forward → red. So a change to `REST_OPS` is auto-covered — no drift.
 */
class GatewayS1Test {

    /** A fake hub that answers `200 "HUB"` to ANY method+path, so a *forwarded* request is observable as non-404. */
    private fun startFakeHub(): Pair<io.ktor.server.engine.EmbeddedServer<*, *>, Int> {
        val hub = embeddedServer(Netty, port = 0) {
            routing { route("{...}") { handle { call.respondText("HUB") } } }
        }.start(wait = false)
        val port = runBlocking { hub.engine.resolvedConnectors().first().port }
        return hub to port
    }

    @Test
    fun everyDataPlaneOpForwards_everyControlPlaneOp404sAtEdge_singleSourced() {
        val (hub, port) = startFakeHub()
        try {
            testApplication {
                application { gatewayModule("http://127.0.0.1:$port") } // default allowlist = fromRestContract()
                var dataPlane = 0
                var control = 0
                for (op in RestContract.REST_OPS) {
                    val concrete = op.path.replace(Regex("\\{[^}]+}"), "x") // fill {id}/{hubId} with a concrete segment
                    val resp = client.request(concrete) { method = HttpMethod.parse(op.method) }
                    if (op.path.startsWith("/api/cp/")) {
                        control++
                        assertEquals(HttpStatusCode.NotFound, resp.status, "control op ${op.method} ${op.path} MUST 404 at the edge")
                    } else {
                        dataPlane++
                        assertNotEquals(HttpStatusCode.NotFound, resp.status, "data-plane op ${op.method} ${op.path} MUST forward (single-sourced from REST_OPS)")
                    }
                }
                // sanity: the whole inventory was actually exercised (not an empty/short loop passing vacuously)
                assertTrue(dataPlane > 30, "exercised the full data-plane ($dataPlane ops)")
                assertTrue(control >= 6, "exercised all /api/cp control ops ($control ops)")
            }
        } finally {
            hub.stop(0, 0)
        }
    }

    @Test
    fun dualMountV1Allowed_andAllowlistIsMethodAndPathPrecise() {
        val (hub, port) = startFakeHub()
        try {
            testApplication {
                application { gatewayModule("http://127.0.0.1:$port") }
                // the /api/v1 dual-mount folds onto the canonical /api templates
                assertNotEquals(HttpStatusCode.NotFound, client.get("/api/v1/agents").status, "/api/v1 dual-mount is allowed")
                // (method, path)-precise: a method not registered for a path is default-denied
                assertEquals(HttpStatusCode.NotFound, client.delete("/api/health").status, "DELETE /api/health is not in REST_OPS → 404")
                // an unknown path is default-denied
                assertEquals(HttpStatusCode.NotFound, client.get("/api/totally-not-a-route").status, "unknown path → 404 at the edge")
            }
        } finally {
            hub.stop(0, 0)
        }
    }
}
