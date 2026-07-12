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
import io.ktor.client.request.get
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
 * CYP-511 — resolve is **owner-gated** (Defense-in-Depth). The differential is **non-vacuous**: the KEY tooth denies a
 * non-owner EVEN WHEN the rendezvous exists (an owner could resolve it), proving the owner-check — not the absence of
 * a rendezvous — is what withholds the id. Mutation (drop the `ownerId == operatorId` check) → the non-owner receives
 * the binding → reds.
 */
class Cyp511ResolveOwnerGateTest {

    private val opToken = "tok-op"
    private val relay = "wss://r.test/relay"

    private fun deps() = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })
    private fun registrar(ownerId: String, hubId: String = "hub_z") =
        HubRegistrar(ConcurrentHashMap(mapOf(hubId to RegisteredHub(hubId, ownerId, "hub", 8787, "s", "d"))))

    private fun ApplicationTestBuilder.app(rz: RelayRendezvous, reg: HubRegistrar) {
        val d = deps()
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            // the operator-token principal maps to op-1 → the owner-check compares the hub's ownerId to "op-1".
            routing { rendezvousRoutes({ rz }, { reg }, d.tokens, d, machineOperatorId = "op-1") }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }
    private val auth = "Bearer $opToken"

    @Test fun owner_resolvesTheRendezvous() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        app(rz, registrar(ownerId = "op-1")) // op-1 OWNS hub_z
        val c = jsonClient()
        c.post("/api/cp/rendezvous/hub_z") { header("Authorization", auth) } // register the rendezvous (not owner-gated)
        val res = c.get("/api/cp/rendezvous/hub_z") { header("Authorization", auth) }.body<RendezvousResolveResponse>()
        assertNotNull(res.binding, "the OWNER (op-1) resolves the rendezvous id")
    }

    @Test fun nonOwner_isDenied_evenWhenRendezvousExists_nonLeaky() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        rz.register("hub_z") // the hub HAS a live rendezvous — an OWNER could resolve it
        app(rz, registrar(ownerId = "op-2")) // but op-2 owns hub_z; the operator-token maps to op-1 → NOT the owner
        val res = jsonClient().get("/api/cp/rendezvous/hub_z") { header("Authorization", auth) }.body<RendezvousResolveResponse>()
        assertNull(res.binding, "a non-owner receives NO binding — the rendezvousId never leaks, even though it exists")
        assertEquals(RendezvousFailure.NOT_REGISTERED, res.failure, "non-owner is NOT_REGISTERED (non-leaky, indistinguishable from absent)")
    }

    @Test fun unknownHub_isDenied() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        app(rz, registrar(ownerId = "op-1")) // registry has hub_z, not hub_unknown
        val res = jsonClient().get("/api/cp/rendezvous/hub_unknown") { header("Authorization", auth) }.body<RendezvousResolveResponse>()
        assertNull(res.binding)
        assertEquals(RendezvousFailure.NOT_REGISTERED, res.failure, "an unknown hub is NOT_REGISTERED (same non-leaky answer)")
    }
}
