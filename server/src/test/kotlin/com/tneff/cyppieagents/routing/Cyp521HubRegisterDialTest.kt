package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.controlplane.HubRendezvousRegistrar
import com.tneff.cyppieagents.controlplane.LiveRelayRendezvous
import com.tneff.cyppieagents.controlplane.RegisteredHub
import com.tneff.cyppieagents.controlplane.RelayRendezvous
import com.tneff.cyppieagents.transport.WebSocketRelayDialer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-521 — the hub obtains its rendezvous id from the CP **register** (the epoch id), then dials with it. This is
 * the FLIP-BLOCKER fix: a static `CYPPIE_REMOTE_RENDEZVOUS` env id can never match `LiveRelayRendezvous.register`'s
 * per-registration epoch id, so the relay would never pair. The KEY tooth proves the id the HUB would dial equals the
 * id a CLIENT resolves — i.e. they pair.
 */
class Cyp521HubRegisterDialTest {

    private val opToken = "tok-op"
    private val relay = "wss://r.test/relay"
    private fun deps() = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })

    private fun ApplicationTestBuilder.cpApp(rz: RelayRendezvous, reg: HubRegistrar) {
        val d = deps()
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { rendezvousRoutes({ rz }, { reg }, d.tokens, d, machineOperatorId = "op-1") }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }
    private fun registrarOwning(ownerId: String) = HubRegistrar(ConcurrentHashMap(mapOf("hub_z" to RegisteredHub("hub_z", ownerId, "hub", 8787, "s", "d"))))

    @Test fun registrar_getsCpEpochId_matchesWhatAClientResolves() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        cpApp(rz, registrarOwning("op-1")) // op-1 OWNS hub_z; the operator-token maps to op-1
        val hubReg = HubRendezvousRegistrar(cpBaseUrl = "", http = jsonClient(), hubId = "hub_z", operatorBearer = { opToken })
        val id = hubReg.register()
        assertNotNull(id, "the hub registers → gets the CP epoch id (owner-gated)")
        // ★ THE FIX: the id the HUB dials with == the id a CLIENT resolves → the relay pairs them (NOT a static env id).
        assertEquals(rz.resolve("hub_z")!!.rendezvousId, id, "hub's registered epoch id == a client's resolved id → pairing works")
    }

    @Test fun registrar_nonOwner_returnsNull_failClosed() = testApplication {
        val rz = LiveRelayRendezvous(relay)
        cpApp(rz, registrarOwning("op-2")) // hub_z owned by op-2; the operator maps to op-1 → NOT the owner
        val id = HubRendezvousRegistrar(cpBaseUrl = "", http = jsonClient(), hubId = "hub_z", operatorBearer = { opToken }).register()
        assertNull(id, "a non-owner hub gets NO id (CYP-516 register owner-gate) → the dialer fails closed")
    }

    @Test fun dialer_nullRendezvous_failsClosed_noDial() = runBlocking {
        val client = HttpClient(CIO) { install(ClientWebSockets) }
        // a null provider (register failed / INERT) → the dialer throws BEFORE opening any socket (fail-closed).
        assertFailsWith<IllegalStateException> { WebSocketRelayDialer(client, rendezvousId = { null }).dial("wss://unused.test/relay") }
        client.close()
    }
}
