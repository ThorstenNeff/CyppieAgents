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
import com.tneff.cyppieagents.contract.RestContract
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-710 — STEP 2: derive the op list FROM the routing tree, and red on anything the tree has that the contract
 * does not declare.
 *
 * ### Why the direction matters (PL, binding)
 * The obvious construction is to iterate `RestContract.REST_OPS` and look each op up in the tree. That builds a
 * guard with the very hole it is meant to close: an endpoint someone adds next month and forgets to declare would
 * not be *mis*-tested, it would be **untested**, and the matrix would still be green. So the **tree is the
 * source**; the contract is the thing checked against it. A route present in the tree and absent from `REST_OPS`
 * fails this test.
 *
 * ### Both directions are checked, for different reasons
 *  - **tree ⊄ contract** (binding): an undeclared endpoint silently escapes the matrix. RED.
 *  - **contract ⊄ tree**: a declared op with no route means the matrix would drive 6 principals at a URL that does
 *    not exist and read the 404s as "denied" — vacuously green cells. Also RED, and it is the same failure class
 *    §8 guards against, one level up.
 *
 * ### Canonical mount only
 * `installPlatform` dual-mounts every REST route under `/api` **and** `/api/v1`, so a raw walk returns each op
 * twice and a 6×65 matrix would silently become 6×130. Only the canonical `/api` mount is enumerated here; the
 * `/api/v1` alias is held to the identical posture by `ApiVersioningGateTest`.
 *
 * ### Third output: declared vs ENFORCED tier
 * Each route also carries the tier actually enforced in the tree — the nearest enclosing
 * `AuthenticatedRouteSelector.required`, or `null` when no structural group encloses it (meaning it is either
 * gated per-handler by `requireCommReader`, or genuinely public). Printing declared-vs-enforced side by side is
 * what turns this from an audit into the input for the matrix: the disagreements are where the interesting cells
 * are, and they are reported rather than asserted, because which side is wrong is a coordinator decision.
 */
class Cyp710RouteEnumerationTest {

    private data class TreeOp(val method: String, val path: String, val enforced: AuthRole?)

    @Test
    fun everyRouteInTheTreeIsDeclared_andEveryDeclaredOpExists() = testApplication {
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true),
            idp = FakeIdentityProvider(mapOf("sess-alice" to ResolvedIdentity("alice", verified = true))),
            roles = SqliteRoleStore(Files.createTempFile("cyp710-enum", ".db"), bootstrapOperatorId = "alice"),
            nowMs = { 1_000L },
        )
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()

        val tree = enumerate(app.routing { })
            .filter { it.path.startsWith("/api") && !it.path.startsWith("/api/v1") }
            .distinctBy { it.method to it.path }
            .sortedWith(compareBy({ it.path }, { it.method }))

        val declared = RestContract.REST_OPS.associateBy { it.method.uppercase() to it.path }
        val treeKeys = tree.map { it.method.uppercase() to it.path }.toSet()

        // ---- the 3-way view: declared tier | enforced tier | presence ----
        val lines = tree.map { op ->
            val d = declared[op.method.uppercase() to op.path]
            "  %-6s %-46s declared=%-18s enforced=%s".format(
                op.method, op.path, d?.tier?.name ?: "<UNDECLARED>", op.enforced?.name ?: "none(handler-or-public)",
            )
        }
        println("CYP710-ENUMERATION tree=${tree.size} declared=${RestContract.REST_OPS.size}\n" + lines.joinToString("\n"))

        // ---- BINDING: the tree is the source. Anything it serves must be declared. ----
        val undeclared = tree.filter { (it.method.uppercase() to it.path) !in declared.keys }
        assertTrue(
            undeclared.isEmpty(),
            "CYP-710: ${undeclared.size} route(s) exist in the routing tree but are NOT declared in " +
                "RestContract.REST_OPS, so the principal matrix would never drive them — an endpoint added and " +
                "not registered is UNTESTED, not mis-tested:\n" +
                undeclared.joinToString("\n") { "  ${it.method} ${it.path} (enforced=${it.enforced?.name ?: "none"})" },
        )

        // ---- the reverse: a declared op with no route would give vacuously "denied" cells ----
        val missing = RestContract.REST_OPS.filter { (it.method.uppercase() to it.path) !in treeKeys }
        assertTrue(
            missing.isEmpty(),
            "CYP-710: ${missing.size} declared op(s) have no route in the tree. The matrix would drive all six " +
                "principals at a non-existent URL and read the 404s as denials — vacuously green cells:\n" +
                missing.joinToString("\n") { "  ${it.method} ${it.path} (declared ${it.tier})" },
        )

        // ---- non-vacuity of this test itself: it must actually have walked a real tree ----
        assertTrue(
            tree.size >= 20,
            "the walk found only ${tree.size} canonical /api routes — the enumeration is broken, and an empty " +
                "walk would make BOTH assertions above trivially true",
        )
    }

    /** Depth-first over the real routing tree; each [HttpMethodRouteSelector] node is one endpoint. */
    private fun enumerate(root: RoutingNode): List<TreeOp> {
        val out = mutableListOf<TreeOp>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += TreeOp(it.method.value, pathOf(node), enforcedRoleOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
    }

    /** The tier actually enforced structurally: the nearest enclosing authenticated group, or null if none. */
    private fun enforcedRoleOf(node: RoutingNode): AuthRole? {
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
        val gitRoot = Files.createTempDirectory("cyp710-enum-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
