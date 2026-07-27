package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.controlplane.LiveRelayRendezvous
import com.tneff.cyppieagents.controlplane.RegisteredHub
import com.tneff.cyppieagents.controlplane.RelayRendezvous
import com.tneff.cyppieagents.controlplane.RendezvousFailure
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-516 — register (`POST /cp/rendezvous/{hubId}`) is now OWNER-gated (symmetric to the CYP-511 resolve gate). The
 * key non-vacuous tooth: a non-owner register is denied AND **does not rotate the epoch** — proving the owner-gate
 * blocks the epoch-rotate DoS (a non-owner rotating would make the hub dial a stale id while the owner resolves a new
 * one → no pairing). Mutation (drop the owner-gate) → the non-owner register rotates → the id changes → reds.
 */
class Cyp516RegisterOwnerGateTest {

    private val opToken = "tok-op"
    private val relay = "wss://r.test/relay"
    private fun deps() = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })
    private fun registrar(ownerId: String, hubId: String = "hub_z") =
        HubRegistrar(ConcurrentHashMap(mapOf(hubId to RegisteredHub(hubId, ownerId, "hub", 8787, "s", "d"))))

    private fun ApplicationTestBuilder.app(rz: RelayRendezvous, reg: HubRegistrar) {
        val d = deps()
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { rendezvousRoutes({ rz }, { reg }, d.tokens, d, machineOperatorId = "op-1") }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }
    private val auth = "Bearer $opToken"

    @Test fun owner_registers_getsBinding() = testApplication {
        app(LiveRelayRendezvous(relay), registrar(ownerId = "op-1")) // op-1 OWNS hub_z
        val res = jsonClient().post("/api/cp/rendezvous/hub_z") { header("Authorization", auth) }.body<RendezvousResolveResponse>()
        assertNotNull(res.binding, "the OWNER registers/rotates its own rendezvous")
    }

    @Test fun nonOwner_registerDenied_doesNotRotate_nonLeaky() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        val pre = rz.register("hub_z")!!.rendezvousId // an EXISTING rendezvous (an owner could resolve it)
        app(rz, registrar(ownerId = "op-2")) // op-2 owns hub_z; the operator maps to op-1 → NOT the owner
        val res = jsonClient().post("/api/cp/rendezvous/hub_z") { header("Authorization", auth) }.body<RendezvousResolveResponse>()
        assertNull(res.binding, "a non-owner register is denied — no binding")
        assertEquals(RendezvousFailure.NOT_REGISTERED, res.failure, "non-owner register → NOT_REGISTERED (non-leaky)")
        assertEquals(pre, rz.resolve("hub_z")!!.rendezvousId, "the non-owner register did NOT rotate the epoch (the DoS is blocked)")
    }

    @Test fun unknownHub_registerDenied() = testApplication {
        app(LiveRelayRendezvous(relay), registrar(ownerId = "op-1")) // registry has hub_z, not hub_unknown
        val res = jsonClient().post("/api/cp/rendezvous/hub_unknown") { header("Authorization", auth) }.body<RendezvousResolveResponse>()
        assertEquals(RendezvousFailure.NOT_REGISTERED, res.failure, "registering an unknown hub is denied (non-leaky)")
        assertNull(res.binding)
    }
}
