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
import com.tneff.cyppieagents.model.HubDescriptor
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-530 (S-J) — `GET /api/cp/hubs` is operator-gated + **owner-scoped**: an operator lists ONLY its own admitted
 * hubs (never another owner's), with `online` derived cheaply+honestly from the live rendezvous and `lastSeen` from
 * `admittedAt`. The key non-vacuous tooth: op-1 does NOT see op-2's hub EVEN THOUGH it is in the shared registry —
 * proving the owner-filter, not the absence of the hub, withholds it. Mutation (drop the owner-filter) → op-2's hub
 * leaks into op-1's list → reds.
 */
class Cyp530HubDiscoveryTest {

    private val opToken = "tok-op"
    private val relay = "wss://r.test/relay"
    private fun deps() = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })

    // Shared registry: op-1 owns hub_a + hub_b; op-2 owns hub_z. Distinct fields so the projection is checkable.
    private fun registrar() = HubRegistrar(ConcurrentHashMap(mapOf(
        "hub_a" to RegisteredHub("hub_a", "op-1", "Hub A", 8787, "sa", "da", admittedAt = 111L),
        "hub_b" to RegisteredHub("hub_b", "op-1", "Hub B", 8080, "sb", "db", admittedAt = 222L),
        "hub_z" to RegisteredHub("hub_z", "op-2", "Hub Z", 9000, "sz", "dz", admittedAt = 333L),
    )))

    private fun ApplicationTestBuilder.app(rz: RelayRendezvous, reg: HubRegistrar, opId: String? = "op-1") {
        val d = deps()
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { hubDiscoveryRoutes({ reg }, { rz }, d.tokens, d, machineOperatorId = opId) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }
    private val auth = "Bearer $opToken"
    private suspend fun ApplicationTestBuilder.list() =
        jsonClient().get("/api/cp/hubs") { header("Authorization", auth) }.body<List<HubDescriptor>>()

    @Test fun listsOnlyOwnHubs_ownerScoped() = testApplication {
        app(InertRelayRendezvous, registrar()) // the operator token maps to op-1
        val hubs = list()
        assertEquals(setOf("hub_a", "hub_b"), hubs.map { it.hubId }.toSet(),
            "op-1 sees ONLY its own hubs (MUT: drop the owner-filter → op-2's hub_z leaks → RED)")
        assertTrue(hubs.none { it.hubId == "hub_z" }, "op-2's hub is never enumerated by op-1")
    }

    @Test fun projectsFields_lastSeenFromAdmittedAt() = testApplication {
        app(InertRelayRendezvous, registrar())
        val a = list().single { it.hubId == "hub_a" }
        assertEquals("Hub A", a.name)
        assertEquals(8787, a.defaultPort)
        assertEquals("da", a.dhPubKey, "dhPubKey (the TOFU-pin value) flows straight through")
        assertEquals(111L, a.lastSeen, "lastSeen = the hub's admittedAt")
    }

    @Test fun online_derivedFromLiveRendezvous() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        rz.register("hub_a") // hub_a has published a live rendezvous → relay-reachable
        app(rz, registrar())
        val hubs = list()
        assertTrue(hubs.single { it.hubId == "hub_a" }.online, "hub_a has a live rendezvous → online=true")
        assertFalse(hubs.single { it.hubId == "hub_b" }.online,
            "hub_b has NO rendezvous → online=false (MUT: hardcode online=true → RED)")
    }

    @Test fun inertRelay_allOffline_honest() = testApplication {
        app(InertRelayRendezvous, registrar())
        assertTrue(list().all { !it.online }, "INERT relay → every hub online=false (honest: no remote reachability)")
    }

    @Test fun blankOperator_ownsNothing_failClosed() = testApplication {
        app(InertRelayRendezvous, registrar(), opId = "") // a blank operator id must own nothing
        assertTrue(list().isEmpty(), "a blank operator owns nothing — never enumerates any hub (fail-closed)")
    }

    @Test fun emptyRegistry_returnsEmpty_inertSafe() = testApplication {
        app(InertRelayRendezvous, HubRegistrar())
        assertTrue(list().isEmpty(), "no admitted hubs → [] (INERT-safe; empty until a hub self-admits)")
    }
}
