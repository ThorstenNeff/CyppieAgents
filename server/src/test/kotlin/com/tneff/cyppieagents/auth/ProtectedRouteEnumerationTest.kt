package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.FakeGit
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
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
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
import kotlin.test.assertTrue

/**
 * CYP-178 / P1 — **RC1 route-enumeration meta-test**: the structural safety-net for the operator-route
 * migration. It walks the **real** Ktor routing tree of a fully-booted platform and asserts that every
 * `/api` endpoint NOT on a small, explicit public allowlist **fails closed (401/403) with no credential**.
 *
 * Why real enumeration (not a hand-list): a new endpoint added OUTSIDE the [authenticatedApi] group — or
 * one whose author forgets `requireOperator`/`requireParticipant` — is discovered automatically and hit
 * here, so it can never ship unguarded. The allowlist is the ONE place a reviewer audits "what is
 * intentionally open"; adding to it is a deliberate, reviewable act.
 *
 * This test is GREEN against the pre-migration tree (every route already fails closed per-handler) and
 * MUST stay green through the migration — that is precisely its job as the net.
 */
class ProtectedRouteEnumerationTest {

    /**
     * The ONLY `/api` endpoints intentionally reachable with no credential (Spec 02 §7): liveness and the
     * content-free client whoami. Everything else on `/api` MUST 401/403 without a credential. A reviewer
     * audits exactly this set.
     *
     * CC1 / CYP-179: `GET /api/agents` was REMOVED from this allowlist — it is now [requireCommReader]-gated
     * (the roster leaked the topology off-localhost). Its absence here means this meta-test now REQUIRES it to
     * fail closed without a credential — the durable structural teeth on the gate (forget the guard → RED).
     */
    private val publicAllowlist = setOf(
        "GET /api/health",
        // CYP-182: the client whoami — public by design (reports {authenticated:false} to an unauthenticated
        // caller instead of a 401) and content-free (no id/email/secrets). Audited here as intentionally open.
        "GET /api/auth/me",
        // CYP-179 / §B(b): the register-wrapper — public by design (anyone may register) and branch-INVARIANT
        // (a new and an existing email get the identical response; see RegisterRoutesTest). Audited as open here.
        "POST /api/auth/register",
    )

    @Test
    fun everyApiRoute_exceptPublicAllowlist_failsClosedWithoutCredential() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = com.tneff.cyppieagents.auth.KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication() // materialize the application + routing tree

        val endpoints = enumerate(app.routing { }).filter { it.path.startsWith("/api") }
        // Sanity: enumeration actually traversed the API surface (guards a silent empty-tree false-pass).
        assertTrue(endpoints.size >= 20, "route enumeration found too few /api endpoints (${endpoints.size}) — tree walk broken?")
        // CYP-234a-2b: normalize the additive /api/v1 alias to /api for the allowlist check, so BOTH prefixes
        // are held to the identical public-allowlist (a public route under only ONE prefix would still be caught).
        val protected = endpoints.filter { "${it.method} ${it.path.replace("/api/v1/", "/api/")}" !in publicAllowlist }
        assertTrue(protected.size >= 15, "too few PROTECTED /api endpoints checked (${protected.size}) — allowlist too wide or walk broken?")

        val leaks = scanForLeaks(protected)
        assertTrue(
            leaks.isEmpty(),
            "these /api routes did NOT fail closed without a credential (add a guard, or — if truly public — the allowlist): $leaks",
        )
    }

    /**
     * The net has TEETH (durable, no manual mutation): mount a deliberately UNGUARDED `/api/leak` alongside
     * the real platform and assert the enumerate-and-probe pass flags it. If this ever fails, the walk has
     * gone blind and the primary test above is a false-pass — this is the tripwire on the tripwire.
     */
    @Test
    fun scanDetectsAnUnguardedApiRoute() = testApplication {
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), settingsClient = com.tneff.cyppieagents.auth.KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
            routing { get("/api/leak") { call.respondText("open") } }
        }
        startApplication()

        val protected = enumerate(app.routing { })
            .filter { it.path.startsWith("/api") && "${it.method} ${it.path}" !in publicAllowlist }
        val leaks = scanForLeaks(protected)
        assertTrue(leaks.any { it.contains("/api/leak") }, "the scan failed to detect the unguarded /api/leak route: $leaks")
    }

    /** Probe each protected endpoint with NO credential; a fail-closed route returns 401/403. */
    private suspend fun ApplicationTestBuilder.scanForLeaks(protected: List<Endpoint>): List<String> {
        val leaks = mutableListOf<String>()
        for (ep in protected) {
            // Substitute a dummy for every path param: auth runs BEFORE any id lookup / body parse, so a
            // fail-closed route returns 401/403 without the id ever being resolved (no existence leak).
            val concrete = ep.path.replace(Regex("\\{[^}]*}"), "metatest-x")
            val resp: HttpResponse = client.request(concrete) { method = HttpMethod.parse(ep.method) }
            if (resp.status != HttpStatusCode.Unauthorized && resp.status != HttpStatusCode.Forbidden) {
                leaks += "${ep.method} ${ep.path} → ${resp.status}"
            }
        }
        return leaks
    }

    // ---- real routing-tree enumeration ----

    private data class Endpoint(val method: String, val path: String)

    /** Depth-first over the real routing tree; each [HttpMethodRouteSelector] node is one endpoint. */
    private fun enumerate(root: RoutingNode): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += Endpoint(it.method.value, pathOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
    }

    /** Reconstructs an endpoint's path by walking parent selectors (Transparent/method selectors contribute nothing). */
    private fun pathOf(node: RoutingNode): String {
        val segments = ArrayDeque<String>()
        var cur: RoutingNode? = node
        while (cur != null) {
            when (val s = cur.selector) {
                is PathSegmentConstantRouteSelector -> segments.addFirst(s.value)
                is PathSegmentParameterRouteSelector -> segments.addFirst("{${s.name}}")
                else -> {} // root / trailing-slash / http-method / transparent auth group → no path segment
            }
            cur = cur.parent
        }
        return "/" + segments.joinToString("/")
    }

    // ---- fake boot (mirrors PlatformWiringTest.bootFake) ----

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    // CYP-179: a fake register-wrapper so `POST /api/auth/register` is actually mounted here — the meta-test
    // then confirms it is present, PUBLIC (no guard), and on the audited allowlist (not a fail-open surprise).
    private fun fakeRegisterMediator(): RegisterMediator {
        val backend = object : KratosRegisterBackend {
            override suspend fun identityExists(email: String) = false
            override suspend fun createAndVerify(email: String, password: String) {}
            override suspend fun notifyExisting(email: String) {}
        }
        return RegisterMediator(backend, dispatch = {})
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("routeenum-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
