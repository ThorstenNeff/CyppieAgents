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
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-710 — STEP 4: the two PL invariants. These are what turn the matrix from a snapshot into a GUARD: both hold
 * today, and neither had anything watching it. They are stated so that the way they would break — by OMISSION —
 * is what reds.
 *
 * **Invariant 1 (consumption side).** A route protected ONLY by an ACL-subject resolver (no `authenticatedApi`
 * group) must keep the participant token READ-ONLY and default its ACL to EMPTY. The failure mode is not a bad
 * check, it is a MISSING one: a new endpoint that wires only a read-resolver bypasses the role gate by omission,
 * and nothing today would go red. So this derives the set of handler-gated routes FROM THE TREE and holds every
 * one of them to the rule — an endpoint added next month is covered without anyone remembering to add it here.
 *
 * **Invariant 2 (producer side).** Public registration creates a NAME, never a role and never authority. Role is
 * not co-created (MEMBER default; OPERATOR only for the deploy-pinned `bootstrapOperatorId`, CYP-196) and
 * authority is not co-created (ACL fail-closed-empty until an operator grants via `PUT /api/acl`).
 *
 * ### Boundary — what these do NOT cover
 * They pin CODE semantics. The known DATA-state case (a legacy pre-CYP-196 OPERATOR row that could beat the pin)
 * is out of scope by design and lives in CYP-712 / the CYP-576 runbook preflight; it must not move an expected
 * value here. Measurement of the durable `SqliteRoleStore` semantics was done separately by the PO assistant
 * (prod is STRICTER than in-memory: DB-UNIQUE `idx_single_operator` + mutex), which is why invariant 2 is
 * asserted as TRUE rather than treated as open.
 */
class Cyp710InvariantsTest {

    private val opToken = "tok-op"
    private val agentToken = "tok-backend"

    private data class TreeOp(val method: String, val path: String, val enforced: AuthRole?)

    // ---------- Invariant 1: handler-gated routes keep participants read-only ----------
    @Test
    fun invariant1_routesWithoutARoleGate_keepParticipantsReadOnly_andDefaultTheAclEmpty() = testApplication {
        val participantTokens = ParticipantTokenStore { 1_000L }
        val roles = SqliteRoleStore(Files.createTempFile("cyp710-inv1", ".db"), bootstrapOperatorId = "alice")
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf(agentToken to "backend"), operatorToken = opToken, loopbackPosture = true),
            idp = FakeIdentityProvider(mapOf("sess-alice" to ResolvedIdentity("alice", verified = true))),
            roles = roles,
            nowMs = { 1_000L },
            participantTokens = participantTokens,
        )
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()

        val ptoken = participantTokens.mint("byo-fresh") // a brand-new subject: no ACL grants anywhere

        val declared = RestContract.REST_OPS.associateBy { it.method.uppercase() to it.path }
        // DERIVED FROM THE TREE, not from a list: every route with no structural auth group, minus the ones
        // declared PUBLIC (those are public by intent, not by omission).
        val handlerGated = enumerate(app.routing { })
            .filter { it.path.startsWith("/api") && !it.path.startsWith("/api/v1") }
            .distinctBy { it.method to it.path }
            .filter { it.enforced == null }
            .filter { declared[it.method.uppercase() to it.path]?.tier != RestContract.Tier.PUBLIC }
            .sortedWith(compareBy({ it.path }, { it.method }))

        println("CYP710-INV1 handler-gated (no structural role gate, non-PUBLIC) routes = ${handlerGated.size}")
        handlerGated.forEach { println("  ${it.method} ${it.path} decl=${declared[it.method.uppercase() to it.path]?.tier}") }

        // Non-vacuity: if the derivation finds nothing, the invariant would hold trivially.
        assertTrue(
            handlerGated.isNotEmpty(),
            "the tree-derived set of handler-gated routes is EMPTY — the derivation is broken and this invariant " +
                "would hold vacuously",
        )

        // READ-ONLY: a participant token must never be admitted to a mutating handler-gated route.
        // CITED EXEMPTION, printed not hidden: POST /api/ws-ticket is a non-GET that is NOT a write in the ACL
        // sense. CYP-286 binds the minted ticket to the CALLER'S OWN read subject and documents the envelope
        // explicitly: "No privilege escalation - a ticket resolves to the SAME read subject the minter already
        // had; it only moves that identity out of a long-lived, loggable query param" (WsTicketStore.kt:12-17).
        // A participant minting one gains nothing it did not already hold, so the read-only invariant is intact.
        // Measured first (it reds this invariant's literal form at 201), then exempted WITH the reason - not
        // relaxed to make the run green.
        val byDesignNonWrites = setOf("/api/ws-ticket")
        val exempted = handlerGated.filter { it.method.uppercase() != "GET" && it.path in byDesignNonWrites }
        if (exempted.isNotEmpty()) {
            println(
                "CYP710-INV1-EXEMPT ${exempted.size} non-GET route(s) exempted as credential-shape, not ACL writes " +
                    "(cited: CYP-286 no-escalation envelope):\n" + exempted.joinToString("\n") { "  ${it.method} ${it.path}" },
            )
        }
        val writes = handlerGated.filter { it.method.uppercase() != "GET" && it.path !in byDesignNonWrites }
        val leaks = mutableListOf<String>()
        for (op in writes) {
            val st = client.request(op.path.replace(Regex("\\{[^}]*}"), "po-backend")) {
                method = HttpMethod.parse(op.method)
                header("Authorization", "Bearer $ptoken")
            }.status
            if (st.value != 401 && st.value != 403) leaks += "  ${op.method} ${op.path} -> ${st.value}"
        }
        assertTrue(
            leaks.isEmpty(),
            "INVARIANT 1 (read-only): a participant token reached ${leaks.size} mutating handler-gated route(s). " +
                "A route protected only by an ACL-subject resolver must keep participants read-only:\n" +
                leaks.joinToString("\n"),
        )

        // ACL DEFAULT EMPTY: a brand-new subject has been granted nothing, so it can write nowhere.
        val writable = client.request("/api/channels/writable") { header("Authorization", "Bearer $ptoken") }
        assertTrue(
            writable.status.value != 401 && writable.status.value != 403,
            "precondition: the participant must be ADMITTED to the writable-channels read, else the emptiness " +
                "below would be meaningless (got ${writable.status})",
        )
        assertEquals(
            "[]", writable.bodyAsText().trim(),
            "INVARIANT 1 (ACL default empty): a freshly minted participant subject must have NO writable channels " +
                "until an operator grants them",
        )
    }

    // ---------- Invariant 2: public registration creates a name, never a role or authority ----------
    @Test
    fun invariant2_publicRegistration_createsANameNeverARoleOrAuthority() = testApplication {
        val roles = SqliteRoleStore(Files.createTempFile("cyp710-inv2", ".db"), bootstrapOperatorId = "alice")
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf(agentToken to "backend"), operatorToken = opToken, loopbackPosture = true),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-alice" to ResolvedIdentity("alice", verified = true),   // the deploy-pinned OPERATOR
                    "sess-newbie" to ResolvedIdentity("newbie", verified = true), // a freshly registered human
                ),
            ),
            roles = roles,
            nowMs = { 1_000L },
        )
        application {
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()

        val before = runBlocking { roles.list() }.size

        val reg = client.request("/api/auth/register") {
            method = HttpMethod.Post
            header("Content-Type", "application/json")
            setBody("""{"email":"newbie@example.com","password":"correct-horse-battery-staple"}""")
        }
        println("CYP710-INV2 register=${reg.status.value} rolesBefore=$before")

        // The registration endpoint itself must not create any local role row.
        val afterRegister = runBlocking { roles.list() }
        assertEquals(
            before, afterRegister.size,
            "INVARIANT 2: POST /api/auth/register must create NO local role assignment — registration creates a " +
                "NAME. Rows before=$before after=${afterRegister.size}",
        )

        // First verified login assigns a role — and it must be MEMBER, never OPERATOR.
        client.request("/api/auth/me") { header("X-Session-Token", "sess-newbie") }
        val newbie = runBlocking { roles.list() }.firstOrNull { it.identityId == "newbie" }
        assertTrue(newbie != null, "the first verified login must assign the newcomer a role row")
        assertEquals(
            AuthRole.MEMBER, newbie.role,
            "INVARIANT 2 (role not co-created): a self-registered human must become MEMBER. OPERATOR is reserved " +
                "for the deploy-pinned bootstrapOperatorId (CYP-196)",
        )

        // Authority is not co-created either: the new MEMBER can write nowhere until an operator grants it.
        val writable = client.request("/api/channels/writable") { header("X-Session-Token", "sess-newbie") }
        assertTrue(
            writable.status.value != 401 && writable.status.value != 403,
            "precondition: the new MEMBER must be ADMITTED to the writable read, else emptiness proves nothing (got ${writable.status})",
        )
        assertEquals(
            "[]", writable.bodyAsText().trim(),
            "INVARIANT 2 (authority not co-created): a freshly registered MEMBER must have NO writable channels " +
                "until an operator grants via PUT /api/acl — the ACL is fail-closed-empty",
        )

        // And the pinned OPERATOR slot was not taken by the newcomer.
        val operators = runBlocking { roles.list() }.filter { it.role == AuthRole.OPERATOR }.map { it.identityId }
        assertTrue(
            "newbie" !in operators,
            "INVARIANT 2: public registration must never yield OPERATOR; operators=$operators",
        )
    }

    // ---- shared helpers ----
    private fun enumerate(root: RoutingNode): List<TreeOp> {
        val out = mutableListOf<TreeOp>()
        fun visit(node: RoutingNode) {
            (node.selector as? HttpMethodRouteSelector)?.let { out += TreeOp(it.method.value, pathOf(node), enforcedRoleOf(node)) }
            node.children.forEach { visit(it) }
        }
        visit(root)
        return out
    }

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
        val secrets = Secrets(mapOf(agentToken to "backend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp710-inv-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
