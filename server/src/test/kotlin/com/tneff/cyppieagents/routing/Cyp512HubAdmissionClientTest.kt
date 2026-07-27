package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.boot.ControlPlaneRegistrar
import com.tneff.cyppieagents.boot.NoOpControlPlaneConnector
import com.tneff.cyppieagents.controlplane.HubAdmissionClient
import com.tneff.cyppieagents.controlplane.HubAdmissionNonce
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-512 — the hub-side [HubAdmissionClient] completes the full live admission round-trip against the CP route
 * (challenge → build signed registration → admit), and the hub lands in the CP registry owned by the AUTHENTICATED
 * operator. This is the part-2 (transport) counterpart to the CP-side teeth — the two ends I own interoperate.
 */
class Cyp512HubAdmissionClientTest {

    @Test
    fun hubClient_endToEnd_admitsHubIntoTheCP() = testApplication {
        val opToken = "tok-op"
        val operator = "op-1" // the operator-token principal maps to op-1
        val cpRegistrar = HubRegistrar()
        val deps = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { hubAdmissionRoutes({ cpRegistrar }, HubAdmissionNonce(), deps.tokens, deps, machineOperatorId = operator) }
        }
        val http = createClient { install(ClientContentNegotiation) { json(CommJson) } }

        val dir = Files.createTempDirectory("cyp512-client")
        SqliteSecretStore(dir.resolve("s.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() }).use { store ->
            val prov = HubIdentityProvisioner(store, dir.resolve(".cyppie/hub-identity.json"))
            val id = prov.ensure()
            // the hub is configured owned by the operator (op-1); the CP re-checks ownerId == the authenticated operator.
            val hubRegistrar = ControlPlaneRegistrar(id, prov, NoOpControlPlaneConnector, ownerId = operator, name = "hub", defaultPort = 8787)
            val client = HubAdmissionClient(cpBaseUrl = "", http = http, registrar = hubRegistrar, operatorBearer = { opToken })

            val result = runBlocking { client.admit() }
            assertTrue(result.admitted, "the hub client completes challenge → register → admit end-to-end")
            assertEquals(id.hubId, result.hubId)
            assertEquals(operator, cpRegistrar.lookup(id.hubId)?.ownerId, "the hub enters the CP registry, owned by the authenticated operator")
        }
    }
}
