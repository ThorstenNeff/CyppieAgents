package com.tneff.cyppieagents.contract

import com.tneff.cyppieagents.FakeGit
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
import io.ktor.server.application.Application
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-234a-2a — the **bidirectional REST-path drift-test** (the gate). [RestContract.REST_OPS] is compared to
 * the REAL `/api` routing tree (DERIVED — same `enumerate(app.routing{})` infra proven on the WS side), in BOTH
 * directions: no real route missing from the contract (no undocumented endpoint), no contract op without a real
 * route (no phantom). `/mcp/hub` is asserted a KNOWN exclusion (present in the tree, excluded on purpose), never
 * silently-missing. The platform is booted with the full auth surface (settings + register mounts) so the
 * enumeration matches production — not a partial boot.
 */
class RestContractDriftTest {

    @Test
    fun restContractDrift_isBidirectional_againstRealRouting() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(
                bootFake(),
                settingsClient = KratosSettingsClient("http://localhost:1"),
                registerMediator = fakeRegisterMediator(),
            )
        }
        startApplication()

        // CYP-234a-2b: the canonical /api surface only (exclude the additive /api/v1 alias — RestContract stays
        // /api-keyed; the /api/v1 ⇔ /api equality is the 2b gate's job in ProtectedRouteEnumerationTest).
        val real = enumerate(app.routing { })
            .filter { (it.path.startsWith("/api/") && !it.path.startsWith("/api/v1")) || it.path.startsWith("/mcp") }
            .map { it.method to it.path }
        val realSet = real.toSet()
        val documented = RestContract.REST_OPS.map { it.method to it.path }.toSet()
        val excluded = RestContract.EXCLUDED_API_PATHS

        assertTrue(real.size >= 40, "enumeration found too few routes (${real.size}) — walk broken or partial boot?")

        // direction 1: every REAL route is documented OR a known exclusion (no silently-undocumented endpoint)
        val undocumented = realSet.filter { it.second !in excluded && it !in documented }.toSet()
        assertEquals(emptySet(), undocumented, "REAL routes missing from RestContract (undocumented endpoints)")

        // direction 2: every documented op is a REAL route (no phantom endpoint in the contract)
        val phantom = documented.filter { it !in realSet }.toSet()
        assertEquals(emptySet(), phantom, "RestContract ops with NO real route (phantom endpoints)")

        // /mcp/hub is genuinely present AND a KNOWN exclusion — not silently missing
        assertTrue(realSet.any { it.second == "/mcp/hub" }, "/mcp/hub should be a real route")
        assertTrue("/mcp/hub" in excluded, "…and it must be an EXPLICIT exclusion (connector wire)")
    }

    @Test
    fun openApi_documentsEveryOp_withComponentsAndErrorEnvelope() {
        val doc = ContractGenerator.openApi()
        val paths = doc["paths"] as JsonObject
        RestContract.REST_OPS.forEach { op ->
            val item = paths[op.path] as? JsonObject
            assertTrue(item != null && item.containsKey(op.method.lowercase()), "OpenAPI missing ${op.method} ${op.path}")
        }
        val schemas = (doc["components"] as JsonObject)["schemas"] as JsonObject
        assertTrue(schemas.containsKey("ApiErrorBody"), "the uniform error envelope must be a component")
        assertTrue(schemas.size >= 15, "too few generated REST components (${schemas.size}) — walk broken?")
    }

    // ---- real routing-tree enumeration (mirrors ProtectedRouteEnumerationTest) ----

    private data class Endpoint(val method: String, val path: String)

    private fun enumerate(root: RoutingNode): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += Endpoint(it.method.value, pathOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
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
        val gitRoot = Files.createTempDirectory("rest-drift").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
