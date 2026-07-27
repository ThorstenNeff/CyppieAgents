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
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
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
import kotlin.test.assertTrue

/**
 * CYP-710 — STEP 3: the principal-class × op matrix, driven against the real Ktor routing tree.
 *
 * ### What this pins that nothing else did
 * `ProtectedRouteEnumerationTest` catches a FORGOTTEN guard (no credential → 401/403) but not a WRONG-TIER one:
 * an op accidentally gated PARTICIPANT instead of OPERATOR is also 401 without a credential, so it stays green.
 * The contract tier only *documents* the intended posture. This matrix drives each op with each principal CLASS
 * and pins which classes the authorization surface actually admits.
 *
 * ### §8 anti-vacuity, enforced structurally rather than by diligence
 * Every "must-not" cell needs a positive counter-probe, or a denial is indistinguishable from a route that does
 * not exist. Rather than hand-pairing 390 cells, the property is enforced on the RESULT: **an op where no
 * principal class was admitted fails the run as INCONCLUSIVE**, not passes. Route existence itself is already
 * proven structurally by step 2's tree-join, which is stricter than an incidental 2xx.
 *
 * Per the coordinator's §8 ruling, "admitted" means **status ∉ {401, 403}** — the auth tier let the caller reach
 * the handler. A 400/404/409 from an authorized class proves auth-passed just as well as a 200, and it lets the
 * ~40 write ops be driven **without valid bodies**, so the matrix never mutates the state it measures. (In the
 * CYP-722 probe a 201 was the evidence; here a 201 would be a contaminant.)
 *
 * ### What is ASSERTED vs what is REPORTED
 * Asserted — the invariants that must hold whatever the tier model turns out to be:
 *  1. no-credential is denied at every op that is not declared PUBLIC (fail-closed);
 *  2. every op admits at least one class (§8 non-vacuity);
 *  3. **no op declared OPERATOR admits a MEMBER-equivalent class** (human MEMBER or agent token) — the
 *     wrong-tier case this ticket exists to catch;
 *  4. no op declared OPERATOR or MEMBER admits a roleless `participant:<subject>`.
 * Reported — the full 390-cell grid and every divergence from the declared tier. Which side of a divergence is
 * wrong (route or contract) is a coordinator decision, so the test measures and prints rather than adjudicating.
 *
 * ### Class (c)
 * `MachineAgent(null)` (kill-switch-downgraded operator token) needs `operatorTokenDisabled=true` AND a
 * pre-existing role-OPERATOR row, so it cannot share this application instance; it is swept separately in
 * [killSwitchClass_sweptAcrossEveryOp] below. Its finding is already filed as CYP-722.
 */
class Cyp710PrincipalOpMatrixTest {

    private val opToken = "tok-op"
    private val agentToken = "tok-backend"

    private data class TreeOp(val method: String, val path: String, val enforced: AuthRole?)

    private fun HttpStatusCode.isAdmitted(): Boolean = value != 401 && value != 403

    /**
     * Path-param substitution, split by method on purpose:
     *  - READS get a REAL existing object (`po-backend` channel / `backend` agent from the boot fixture). With a
     *    non-existent id an ACL-scoped read is fail-closed 403 for EVERY class, which looks like "no class
     *    admitted" and would mask the tier it is supposed to measure. Measured: that artefact hit
     *    GET /api/channels/{id}/messages and /share on the first run.
     *  - WRITES keep a non-existent id so a mutating op can never reach a real object. Combined with sending no
     *    body, a write is rejected before it can change the state this matrix is measuring.
     */
    private fun concrete(method: String, path: String): String {
        val id = if (method.uppercase() == "GET") {
            when {
                path.startsWith("/api/channels") -> "po-backend"
                path.startsWith("/api/agents") -> "backend"
                else -> "cyp710-nonexistent"
            }
        } else {
            "cyp710-nonexistent"
        }
        return path.replace(Regex("\\{[^}]*}"), id)
    }

    /**
     * Routes whose mounting/behaviour depends on ambient env at install time (PlatformWiring reads
     * CYPPIE_TERMINAL_DELEGATION_ENABLED / CYPPIE_REMOTE_RELAY_URL). When the feature is off they deny EVERY
     * class, which is not a tier finding — it is the feature being disabled. They are exempted from the §8
     * reachability rule LOUDLY (printed, never silently dropped) so the report states its own coverage honestly.
     */
    private fun isEnvGated(path: String) = path.contains("/terminal-grants") || path.contains("/rendezvous")

    @Test
    fun principalClassesTimesOps_pinsTheAdmittedClassPerOp() = testApplication {
        val participantTokens = ParticipantTokenStore { 1_000L }
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf(agentToken to "backend"), operatorToken = opToken, loopbackPosture = true),
            idp = FakeIdentityProvider(
                mapOf(
                    "sess-alice" to ResolvedIdentity("alice", verified = true),
                    "sess-carol" to ResolvedIdentity("carol", verified = true),
                ),
            ),
            roles = SqliteRoleStore(Files.createTempFile("cyp710-matrix", ".db"), bootstrapOperatorId = "alice"),
            nowMs = { 1_000L },
            participantTokens = participantTokens,
        )
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()

        // Pinned identity FIRST — the single-OPERATOR slot must not go to carol.
        client.request("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.request("/api/auth/me") { header("X-Session-Token", "sess-carol") }
        val participantToken = participantTokens.mint("byo-reader")

        val classes = linkedMapOf<String, HttpRequestBuilder.() -> Unit>(
            "a-MachineOperator" to { header("Authorization", "Bearer $opToken") },
            "b-MachineAgent(id)" to { header("Authorization", "Bearer $agentToken") },
            "d-HumanOPERATOR" to { header("X-Session-Token", "sess-alice") },
            "e-HumanMEMBER" to { header("X-Session-Token", "sess-carol") },
            "f-participant" to { header("Authorization", "Bearer $participantToken") },
            "z-noCredential" to { },
        )

        val ops = enumerate(app.routing { })
            .filter { it.path.startsWith("/api") && !it.path.startsWith("/api/v1") }
            .distinctBy { it.method to it.path }
            .sortedWith(compareBy({ it.path }, { it.method }))

        val declared = RestContract.REST_OPS.associateBy { it.method.uppercase() to it.path }

        // ---- drive the grid ----
        val grid = linkedMapOf<TreeOp, Map<String, HttpStatusCode>>()
        for (op in ops) {
            val row = linkedMapOf<String, HttpStatusCode>()
            for ((name, cred) in classes) {
                row[name] = client.request(concrete(op.method, op.path)) { method = HttpMethod.parse(op.method); cred() }.status
            }
            grid[op] = row
        }

        // ---- report the whole grid, plus divergences ----
        val header = "CYP710-MATRIX ops=${grid.size} classes=${classes.size} cells=${grid.size * classes.size}"
        val body = grid.entries.joinToString("\n") { (op, row) ->
            val tier = declared[op.method.uppercase() to op.path]?.tier?.name ?: "<UNDECLARED>"
            "  %-6s %-44s decl=%-17s enf=%-9s %s".format(
                op.method, op.path, tier, op.enforced?.name ?: "none",
                row.entries.joinToString(" ") { "${it.key.substringBefore('-')}=${it.value.value}" },
            )
        }
        println("$header\n$body")

        // ---- ASSERTION 1: fail-closed for the anonymous caller on everything not declared PUBLIC ----
        val anonLeaks = grid.entries.filter { (op, row) ->
            val tier = declared[op.method.uppercase() to op.path]?.tier
            tier != RestContract.Tier.PUBLIC && row.getValue("z-noCredential").isAdmitted()
        }
        assertTrue(
            anonLeaks.isEmpty(),
            "no-credential must be denied at every non-PUBLIC op; admitted at:\n" +
                anonLeaks.joinToString("\n") { (op, row) -> "  ${op.method} ${op.path} -> ${row["z-noCredential"]}" },
        )

        // ---- ASSERTION 2 (§8): every op must admit SOMEONE, or its denials prove nothing ----
        val allUnreachable = grid.entries.filter { (_, row) -> row.none { it.value.isAdmitted() } }
        val envGatedUnreachable = allUnreachable.filter { isEnvGated(it.key.path) }
        if (envGatedUnreachable.isNotEmpty()) {
            println(
                "CYP710-COVERAGE ${envGatedUnreachable.size} op(s) exempted from the §8 reachability rule because " +
                    "they are env-gated and the feature is OFF in this run (NOT a tier finding, and NOT silently " +
                    "dropped):\n" + envGatedUnreachable.joinToString("\n") { "  ${it.key.method} ${it.key.path}" },
            )
        }
        val unreachable = allUnreachable.filterNot { isEnvGated(it.key.path) }
        assertTrue(
            unreachable.isEmpty(),
            "INCONCLUSIVE, not a pass: ${unreachable.size} op(s) admitted NO principal class, so their denial " +
                "cells are indistinguishable from a route that does not exist:\n" +
                unreachable.joinToString("\n") { (op, row) -> "  ${op.method} ${op.path} -> ${row.values.map { it.value }}" },
        )

        // ---- ASSERTION 3: the wrong-tier case this ticket exists to catch ----
        val memberOnOperatorOps = grid.entries.filter { (op, row) ->
            declared[op.method.uppercase() to op.path]?.tier == RestContract.Tier.OPERATOR &&
                (row.getValue("e-HumanMEMBER").isAdmitted() || row.getValue("b-MachineAgent(id)").isAdmitted())
        }
        assertTrue(
            memberOnOperatorOps.isEmpty(),
            "WRONG TIER: ${memberOnOperatorOps.size} op(s) declared OPERATOR admit a MEMBER-equivalent class " +
                "(human MEMBER or agent token). This is exactly the case a no-credential probe cannot see:\n" +
                memberOnOperatorOps.joinToString("\n") { (op, row) ->
                    "  ${op.method} ${op.path} member=${row["e-HumanMEMBER"]} agent=${row["b-MachineAgent(id)"]}"
                },
        )

        // ---- ASSERTION 4: a roleless participant must not reach a role-gated op ----
        val participantOnRoleOps = grid.entries.filter { (op, row) ->
            val tier = declared[op.method.uppercase() to op.path]?.tier
            (tier == RestContract.Tier.OPERATOR || tier == RestContract.Tier.MEMBER) && row.getValue("f-participant").isAdmitted()
        }
        assertTrue(
            participantOnRoleOps.isEmpty(),
            "a roleless participant:<subject> reached ${participantOnRoleOps.size} role-gated op(s):\n" +
                participantOnRoleOps.joinToString("\n") { (op, row) -> "  ${op.method} ${op.path} -> ${row["f-participant"]}" },
        )
    }

    /**
     * Class (c) across every op. Kept separate because the kill-switch needs its own application instance.
     * Asserts only the safety invariant (the downgraded token must not reach a declared-OPERATOR op) and prints
     * the rest — the comm-surface admission is CYP-722 and is the coordinator's to grade, not this test's.
     */
    @Test
    fun killSwitchClass_sweptAcrossEveryOp() = testApplication {
        val authDeps = AuthDeps(
            tokens = TokenRegistry(mapOf(agentToken to "backend"), operatorToken = opToken, loopbackPosture = true),
            idp = FakeIdentityProvider(mapOf("sess-alice" to ResolvedIdentity("alice", verified = true))),
            roles = SqliteRoleStore(Files.createTempFile("cyp710-ks", ".db"), bootstrapOperatorId = "alice"),
            nowMs = { 1_000L },
            operatorTokenDisabled = true,
        )
        lateinit var app: Application
        application {
            app = this
            installPlatform(bootFake(), authDeps, settingsClient = KratosSettingsClient("http://localhost:1"), registerMediator = fakeRegisterMediator())
        }
        startApplication()
        client.request("/api/auth/me") { header("X-Session-Token", "sess-alice") } // arm the kill-switch

        val declared = RestContract.REST_OPS.associateBy { it.method.uppercase() to it.path }
        val ops = enumerate(app.routing { })
            .filter { it.path.startsWith("/api") && !it.path.startsWith("/api/v1") }
            .distinctBy { it.method to it.path }
            .sortedWith(compareBy({ it.path }, { it.method }))

        val admitted = mutableListOf<String>()
        val onOperatorOps = mutableListOf<String>()
        for (op in ops) {
            val st = client.request(concrete(op.method, op.path)) { method = HttpMethod.parse(op.method); header("Authorization", "Bearer $opToken") }.status
            val tier = declared[op.method.uppercase() to op.path]?.tier
            if (st.isAdmitted()) {
                admitted += "  ${op.method} ${op.path} (decl=$tier) -> ${st.value}"
                if (tier == RestContract.Tier.OPERATOR) onOperatorOps += "  ${op.method} ${op.path} -> ${st.value}"
            }
        }
        println("CYP710-KILLSWITCH-SWEEP admittedOps=${admitted.size}/${ops.size}\n" + admitted.joinToString("\n"))

        assertTrue(
            onOperatorOps.isEmpty(),
            "CYP-186 C.2: the kill-switch-downgraded operator token reached ${onOperatorOps.size} op(s) declared " +
                "OPERATOR — the demotion must hold on the structural tier:\n" + onOperatorOps.joinToString("\n"),
        )
    }

    // ---- enumeration (identical walk to step 2) ----
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
        val gitRoot = Files.createTempDirectory("cyp710-matrix-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
