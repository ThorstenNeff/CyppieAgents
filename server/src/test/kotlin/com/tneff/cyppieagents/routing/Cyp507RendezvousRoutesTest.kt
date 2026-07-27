package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.controlplane.InertRelayRendezvous
import com.tneff.cyppieagents.controlplane.LiveRelayRendezvous
import com.tneff.cyppieagents.controlplane.RegisteredHub
import com.tneff.cyppieagents.controlplane.RelayRendezvous
import com.tneff.cyppieagents.controlplane.RendezvousFailure
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import java.util.concurrent.ConcurrentHashMap
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-507 — the CP rendezvous endpoint. Operator-gated (fail-closed), INERT-safe (404/503 with no live relay), and a
 * live register→resolve round-trip that returns the SAME opaque id.
 */
class Cyp507RendezvousRoutesTest {

    private val opToken = "tok-op"
    private fun deps() = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })

    // CYP-511: the resolve tests own their hubs by op-1 (the operator-token principal), so the owner-gate admits them.
    private fun ownedRegistrar(vararg hubIds: String, ownerId: String = "op-1") =
        HubRegistrar(ConcurrentHashMap(hubIds.associateWith { RegisteredHub(it, ownerId, "hub", 8787, "s", "d") }))

    private fun ApplicationTestBuilder.app(
        rendezvous: RelayRendezvous,
        registrar: HubRegistrar = ownedRegistrar("hub_x", "hub_y", "never_registered"),
    ) {
        val d = deps()
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { rendezvousRoutes({ rendezvous }, { registrar }, d.tokens, d, machineOperatorId = "op-1") }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test fun resolve_requiresOperator_noCredentialIs401() = testApplication {
        app(LiveRelayRendezvous("wss://r.test/relay"))
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/cp/rendezvous/hub_x").status, "the rendezvous endpoint is operator-gated (fail-closed)")
    }

    @Test fun inert_bothVerbs_200_relayUnavailable() = testApplication {
        app(InertRelayRendezvous)
        val c = jsonClient()
        // business outcome = 200 + typed failure (not an HTTP error) — INERT = RELAY_UNAVAILABLE, both verbs.
        val resolve = c.get("/api/cp/rendezvous/hub_x") { header("Authorization", "Bearer $opToken") }
        assertEquals(HttpStatusCode.OK, resolve.status)
        assertEquals(RendezvousFailure.RELAY_UNAVAILABLE, resolve.body<RendezvousResolveResponse>().failure, "INERT resolve → RELAY_UNAVAILABLE")
        val register = c.post("/api/cp/rendezvous/hub_x") { header("Authorization", "Bearer $opToken") }
        assertEquals(HttpStatusCode.OK, register.status)
        assertEquals(RendezvousFailure.RELAY_UNAVAILABLE, register.body<RendezvousResolveResponse>().failure, "INERT register → RELAY_UNAVAILABLE")
    }

    @Test fun live_resolveBeforeRegister_isNotRegistered() = testApplication {
        app(LiveRelayRendezvous("wss://r.test/relay"))
        val res = jsonClient().get("/api/cp/rendezvous/never_registered") { header("Authorization", "Bearer $opToken") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(RendezvousFailure.NOT_REGISTERED, res.body<RendezvousResolveResponse>().failure, "live but no rendezvous → NOT_REGISTERED (distinct from RELAY_UNAVAILABLE)")
    }

    @Test fun live_registerThenResolve_roundTrips_sameOpaqueId() = testApplication {
        app(LiveRelayRendezvous("wss://r.test/relay"))
        val c = jsonClient()
        val reg = c.post("/api/cp/rendezvous/hub_y") { header("Authorization", "Bearer $opToken") }.body<RendezvousResolveResponse>()
        assertEquals("wss://r.test/relay", reg.binding!!.relayUrl)
        val res = c.get("/api/cp/rendezvous/hub_y") { header("Authorization", "Bearer $opToken") }.body<RendezvousResolveResponse>()
        assertEquals(reg.binding!!.rendezvousId, res.binding!!.rendezvousId, "resolve returns the registered opaque rendezvous id")
    }
}
