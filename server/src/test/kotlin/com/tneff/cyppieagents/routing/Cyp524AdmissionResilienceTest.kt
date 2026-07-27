package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.boot.ControlPlaneRegistrar
import com.tneff.cyppieagents.boot.NoOpControlPlaneConnector
import com.tneff.cyppieagents.controlplane.AdmissionRejectedException
import com.tneff.cyppieagents.controlplane.AdmissionRetry
import com.tneff.cyppieagents.controlplane.HubAdmissionClient
import com.tneff.cyppieagents.controlplane.HubAdmissionNonce
import com.tneff.cyppieagents.controlplane.HubAdmissionResult
import com.tneff.cyppieagents.controlplane.HubChallenge
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.MasterKeyCustody
import com.tneff.cyppieagents.crypto.SecretCipherFactory
import com.tneff.cyppieagents.crypto.SqliteSecretStore
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-524 — the hub self-admits at boot by dialing its OWN edge (`CYPPIE_CP_URL` → Caddy → 127.0.0.1:<port> = this
 * process). The admit coroutine can fire before this process' listener has bound → the edge returns a NON-JSON body
 * (502 / warmup page / connection-refused). The old client did a blind `.body<HubChallenge>()` with no retry, so
 * that non-JSON became an opaque `SourceByteReadChannel` and one lost race left the hub unregistered until restart.
 *
 * These teeth drive the **real deploy wire-shape** (real Tier.OPERATOR auth path + prod-faithful StatusPages), and
 * the transient-edge symptom (a MockEngine that reproduces the exact 502 `text/html` the fixture was blind to).
 */
class Cyp524AdmissionResilienceTest {

    private val nonceB64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")
    private fun htmlHeaders() = headersOf(HttpHeaders.ContentType, "text/html")

    /** Build a real [ControlPlaneRegistrar] (real HubIdentity via a temp SecretStore) owned by [operator]. */
    private suspend fun withRegistrar(operator: String, block: suspend (ControlPlaneRegistrar, String) -> Unit) {
        val dir = Files.createTempDirectory("cyp524")
        SqliteSecretStore(dir.resolve("s.db"), MasterKeyCustody { SecretCipherFactory.newBoxKeyset() }).use { store ->
            val prov = HubIdentityProvisioner(store, dir.resolve(".cyppie/hub-identity.json"))
            val id = prov.ensure()
            block(ControlPlaneRegistrar(id, prov, NoOpControlPlaneConnector, ownerId = operator, name = "hub", defaultPort = 8787), id.hubId)
        }
    }

    // ── Tooth (a): the REAL deploy wire-shape, through the real Tier.OPERATOR auth path + prod-faithful StatusPages ──

    @Test
    fun realWire_challengeAndAdmit_deserializeThroughRealAuthAndProdStatusPages() = testApplication {
        val opToken = "tok-op"
        val operator = "op-1"
        val cpRegistrar = HubRegistrar()
        val deps = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })
        application {
            install(ContentNegotiation) { json(CommJson) }
            // ★ prod-faithful StatusPages: the SAME ApiErrorBody JSON installPlatform (PlatformWiring:45) emits — so
            // this fixture's error shape == the real deploy's, not a bespoke `respond(status)` that hides content-type.
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) } }
            routing { hubAdmissionRoutes({ cpRegistrar }, HubAdmissionNonce(), deps.tokens, deps, machineOperatorId = operator) }
        }
        val http = createClient { install(ClientContentNegotiation) { json(CommJson) } }
        withRegistrar(operator) { registrar, hubId ->
            val client = HubAdmissionClient(cpBaseUrl = "", http = http, registrar = registrar, operatorBearer = { opToken })
            val result = client.admit()
            assertTrue(result.admitted, "the real serialized HubChallenge deserializes through the real client CN → admit")
            assertEquals(operator, cpRegistrar.lookup(hubId)?.ownerId, "the hub entered the CP registry, owned by the authenticated operator")
        }
    }

    @Test
    fun realWire_wrongBearer_realJson401_isDiagnosticTerminal_notOpaque_noRetry() = testApplication {
        val opToken = "tok-op"
        val operator = "op-1"
        val deps = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { 1_000L })
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) } }
            routing { hubAdmissionRoutes({ HubRegistrar() }, HubAdmissionNonce(), deps.tokens, deps, machineOperatorId = operator) }
        }
        val http = createClient { install(ClientContentNegotiation) { json(CommJson) } }
        withRegistrar(operator) { registrar, _ ->
            // a WRONG bearer → the real gate 401s → prod StatusPages renders JSON ApiErrorBody. The guard classifies a
            // CP-served 4xx as TERMINAL (auth never self-heals) and surfaces the concrete status — NOT the opaque
            // `SourceByteReadChannel` the old blind `.body<HubChallenge>()` would throw on the JSON error body.
            val client = HubAdmissionClient("", http, registrar, operatorBearer = { "not-the-operator" },
                retry = AdmissionRetry(maxAttempts = 4, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
            val ex = assertFailsWith<AdmissionRejectedException> { runBlocking { client.admit() } }
            assertTrue(ex.message!!.contains("401"), "the diagnostic carries the real status (401): ${ex.message}")
        }
    }

    // ── Tooth (a2): the exact production symptom — a NON-JSON edge (502 text/html) is diagnostic, never opaque ──

    @Test
    fun transientEdge502Html_isDiagnostic_notSourceByteReadChannel() = runBlocking {
        withRegistrar("op-1") { registrar, _ ->
            val engine = MockEngine { respond("<html>502 Bad Gateway</html>", HttpStatusCode.BadGateway, htmlHeaders()) }
            val http = HttpClient(engine) { install(ClientContentNegotiation) { json(CommJson) } }
            // maxAttempts=1 → surface the FIRST classification (not the exhausted-retry wrapper), to assert the guard.
            val client = HubAdmissionClient("http://cp.test", http, registrar, operatorBearer = { "op" },
                retry = AdmissionRetry(maxAttempts = 1, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
            // maxAttempts=1 exhausts immediately → a terminal, DIAGNOSTIC result carrying 502 + text/html (the old
            // blind path threw an opaque NoTransformationFoundException / SourceByteReadChannel instead).
            val result = client.admit()
            assertFalse(result.admitted, "a 502 edge is not an admission")
            val reason = result.reason ?: ""
            assertTrue(reason.contains("502"), "the reason names the real status: $reason")
            assertTrue(reason.contains("text/html"), "the reason names the real content-type: $reason")
        }
    }

    // ── Tooth (b): RETRY non-vacuous — admission succeeds after N transient edge-502s; without retry it fails ──

    @Test
    fun retry_succeedsAfterTransientEdge502s_nonVacuous() = runBlocking {
        withRegistrar("op-1") { registrar, hubId ->
            var challengeCalls = 0
            val engine = MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/cp/challenge") -> {
                        challengeCalls++
                        if (challengeCalls < 3) {
                            respond("<html>502 Bad Gateway (upstream not up)</html>", HttpStatusCode.BadGateway, htmlHeaders())
                        } else {
                            respond(CommJson.encodeToString(HubChallenge(nonceB64)), HttpStatusCode.OK, jsonHeaders())
                        }
                    }
                    request.url.encodedPath.endsWith("/cp/admit") ->
                        respond(CommJson.encodeToString(HubAdmissionResult(admitted = true, hubId = hubId)), HttpStatusCode.OK, jsonHeaders())
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
            val http = HttpClient(engine) { install(ClientContentNegotiation) { json(CommJson) } }
            val client = HubAdmissionClient("http://cp.test", http, registrar, operatorBearer = { "op" },
                retry = AdmissionRetry(maxAttempts = 5, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
            val result = client.admit()
            assertTrue(result.admitted, "admission succeeds after 2 transient edge-502s were retried")
            assertEquals(3, challengeCalls, "the challenge was retried until the edge served JSON (2 transient + 1 ok)")
        }
    }

    @Test
    fun connectionRefused_isTransient_retried() = runBlocking {
        withRegistrar("op-1") { registrar, hubId ->
            var challengeCalls = 0
            val engine = MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/cp/challenge") -> {
                        challengeCalls++
                        if (challengeCalls < 2) throw java.net.ConnectException("Connection refused")
                        respond(CommJson.encodeToString(HubChallenge(nonceB64)), HttpStatusCode.OK, jsonHeaders())
                    }
                    else -> respond(CommJson.encodeToString(HubAdmissionResult(admitted = true, hubId = hubId)), HttpStatusCode.OK, jsonHeaders())
                }
            }
            val http = HttpClient(engine) { install(ClientContentNegotiation) { json(CommJson) } }
            val client = HubAdmissionClient("http://cp.test", http, registrar, operatorBearer = { "op" },
                retry = AdmissionRetry(maxAttempts = 4, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
            assertTrue(client.admit().admitted, "a boot-window connection-refused is transient → retried to success")
            assertEquals(2, challengeCalls)
        }
    }

    @Test
    fun retryBounded_exhaustsToTerminalResult_neverSilentSuccess() = runBlocking {
        withRegistrar("op-1") { registrar, _ ->
            val engine = MockEngine { respond("<html>502</html>", HttpStatusCode.BadGateway, htmlHeaders()) }
            val http = HttpClient(engine) { install(ClientContentNegotiation) { json(CommJson) } }
            val client = HubAdmissionClient("http://cp.test", http, registrar, operatorBearer = { "op" },
                retry = AdmissionRetry(maxAttempts = 3, baseDelayMs = 1, maxDelayMs = 1), sleep = {})
            val result = client.admit()
            assertFalse(result.admitted, "persistent 502 → bounded retry exhausts to a terminal not-admitted (no infinite loop, no silent success)")
            assertTrue(result.reason!!.contains("cp_unreachable_after_retries"), "the reason is diagnostic: ${result.reason}")
        }
    }

    @Test
    fun retryDelay_isBoundedAndMonotoneToCap() {
        val r = AdmissionRetry(baseDelayMs = 250, maxDelayMs = 4_000, factor = 2.0)
        assertEquals(250, r.delayForAttempt(1))
        assertEquals(500, r.delayForAttempt(2))
        assertEquals(1_000, r.delayForAttempt(3))
        assertEquals(4_000, r.delayForAttempt(10), "capped at maxDelayMs — never unbounded")
    }
}
