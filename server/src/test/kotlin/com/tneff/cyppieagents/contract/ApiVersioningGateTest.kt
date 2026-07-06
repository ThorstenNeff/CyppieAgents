package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.FakeGit
import com.tneff.cyppieagents.auth.AuthRole
import com.tneff.cyppieagents.auth.AuthenticatedRouteSelector
import com.tneff.cyppieagents.auth.KratosRegisterBackend
import com.tneff.cyppieagents.auth.KratosSettingsClient
import com.tneff.cyppieagents.auth.RegisterMediator
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-234a-2b — the ROUTING-CRITICAL versioning gate. `/api` is the LIVE surface; `/api/v1` is the additive
 * alias, mounted by the SAME loop body (identical by construction). This proves, from the REAL routing tree:
 *  1. **`/api` byte-identical** to the 42-op `RestContract` (the live-regression tripwire — no `/api` route
 *     added / dropped / renamed by the refactor).
 *  2. **Both prefixes equal**, both directions (every `/api/x` has a `/api/v1/x` and vice-versa).
 *  3. **CYP-272 tier-tooth:** each `/api` route's EFFECTIVE enforced tier (read structurally from the
 *     [AuthenticatedRouteSelector] role + a no-credential probe) == the documented `RestContract.tier` — binding
 *     the OpenAPI `security` to the real gate. (The `/api/events` OPERATOR→MEMBER desync just fixed is the
 *     historical witness this would have caught.)
 *
 * (Fail-closed under BOTH prefixes is covered by `ProtectedRouteEnumerationTest`, which now enumerates both.)
 */
class ApiVersioningGateTest {

    private data class Ep(val method: String, val path: String, val authRole: AuthRole?)

    @Test
    fun api_isByteIdentical_toRestContract() = withPlatform { app ->
        val api = canonicalApi(app).map { it.method to it.path }.toSet()
        val documented = RestContract.REST_OPS.map { it.method to it.path }.toSet()
        assertEquals(emptySet(), documented - api, "RestContract op with NO real /api route (refactor dropped/renamed it?)")
        assertEquals(emptySet(), api - documented, "real /api route NOT in RestContract (refactor added an /api route?)")
    }

    @Test
    fun apiV1_mirrors_api_bothDirections() = withPlatform { app ->
        val all = enumerateWithRole(app.routing { })
        val api = all.filter { it.path.startsWith("/api/") && !it.path.startsWith("/api/v1") }.map { it.method to it.path }.toSet()
        val v1 = all.filter { it.path.startsWith("/api/v1/") }.map { it.method to ("/api" + it.path.removePrefix("/api/v1")) }.toSet()
        assertTrue(v1.isNotEmpty(), "no /api/v1 routes found — the dual-mount loop did not run?")
        assertEquals(emptySet(), api - v1, "/api routes missing their /api/v1 mirror")
        assertEquals(emptySet(), v1 - api, "/api/v1 routes with no /api original")
    }

    @Test
    fun tierTooth_eachApiRoute_enforcedTier_matches_restContractTier() = withPlatformProbe { app, probe ->
        val roleByOp = canonicalApi(app).associate { (it.method to it.path) to it.authRole }
        for (op in RestContract.REST_OPS) {
            val authRole = roleByOp[op.method to op.path]
            val observed: ObservedTier = when {
                authRole == AuthRole.OPERATOR -> ObservedTier.OPERATOR
                authRole == AuthRole.MEMBER -> ObservedTier.MEMBER
                else -> {
                    // no structural auth-group → distinguish participant (in-handler gate) from public via a
                    // no-credential probe (auth runs before any work → fail-closed routes 401/403 with no side-effect).
                    val status = probe(op.method, op.path)
                    if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) ObservedTier.PARTICIPANT else ObservedTier.PUBLIC
                }
            }
            assertEquals(op.tier.observed(), observed, "TIER DESYNC ${op.method} ${op.path}: RestContract=${op.tier} but the real gate enforces $observed")
        }
    }

    // ---- tier model ----

    private enum class ObservedTier { PUBLIC, PARTICIPANT, MEMBER, OPERATOR }

    // PARTICIPANT_WRITE folds to PARTICIPANT: the write/ACL distinction is downstream, not an authentication tier
    // that OpenAPI `security` expresses (the tooth binds the AUTH tier).
    private fun RestContract.Tier.observed(): ObservedTier = when (this) {
        RestContract.Tier.PUBLIC -> ObservedTier.PUBLIC
        RestContract.Tier.PARTICIPANT, RestContract.Tier.PARTICIPANT_WRITE -> ObservedTier.PARTICIPANT
        RestContract.Tier.MEMBER -> ObservedTier.MEMBER
        RestContract.Tier.OPERATOR -> ObservedTier.OPERATOR
    }

    // ---- routing-tree enumeration (canonical /api only) + the structural auth role ----

    private fun canonicalApi(app: Application): List<Ep> =
        enumerateWithRole(app.routing { }).filter { it.path.startsWith("/api/") && !it.path.startsWith("/api/v1") }

    private fun enumerateWithRole(root: RoutingNode): List<Ep> {
        val out = mutableListOf<Ep>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += Ep(it.method.value, pathOf(node), authRoleOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
    }

    private fun authRoleOf(node: RoutingNode): AuthRole? {
        var cur: RoutingNode? = node
        while (cur != null) {
            (cur.selector as? AuthenticatedRouteSelector)?.let { return it.required }
            cur = cur.parent
        }
        return null
    }

    private fun pathOf(node: RoutingNode): String {
        val segments = ArrayDeque<String>()
        var cur: RoutingNode? = node
        while (cur != null) {
            when (val s = cur.selector) {
                is PathSegmentConstantRouteSelector -> segments.addFirst(s.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{${s.name}}")
                else -> {}
            }
            cur = cur.parent
        }
        return "/" + segments.joinToString("/")
    }

    // ---- harness ----

    private fun withPlatform(body: ApplicationTestBuilder.(Application) -> Unit) = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()
        body(app)
    }

    private fun withPlatformProbe(body: suspend ApplicationTestBuilder.(Application, suspend (String, String) -> HttpStatusCode) -> Unit) = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()
        val probe: suspend (String, String) -> HttpStatusCode = { method, path ->
            val concrete = path.replace(Regex("\\{[^}]*}"), "metatest-x")
            val resp: HttpResponse = client.request(concrete) { this.method = HttpMethod.parse(method) }
            resp.status
        }
        body(app, probe)
    }

    private fun fakeRegisterMediator(): RegisterMediator {
        val backend = object : KratosRegisterBackend {
            override suspend fun identityExists(email: String) = false
            override suspend fun createAndVerify(email: String, password: String) {}
            override suspend fun notifyExisting(email: String) {}
        }
        return RegisterMediator(backend, dispatch = {})
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("api-versioning-gate").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
