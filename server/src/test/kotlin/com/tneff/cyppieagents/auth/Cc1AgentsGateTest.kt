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
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CC1 / CYP-179 — the `GET /api/agents` roster gate. It WAS anonymous ("agents carry no secrets"), but the
 * body carries the topology (agent ids, names, roles, worktree names, connectorKind, provider) — off-localhost
 * that is a personnel/topology disclosure to any unauthenticated caller. It is now [requireCommReader]-gated,
 * exactly like its sibling comm reads (`/channels`, `/inbox`, `/acl`): closed to the anonymous public, open to
 * every authenticated reader — agent token, operator token, AND a verified human OPERATOR/MEMBER session (the
 * chosen tier over token-only `requireParticipant`, which would also 401 a human OPERATOR-by-session — a
 * regression with no security upside). The route-enum meta-test (allowlist removal) is the STRUCTURAL teeth;
 * this pins the population + proves the anonymous body leaks nothing.
 */
class Cc1AgentsGateTest {

    private val opToken = "tok-op"
    private val memberId = "member-id"

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true),
        idp = FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true), // first-verified → OPERATOR
                "sess-member" to ResolvedIdentity(memberId, verified = true),   // later → MEMBER
            ),
        ),
        roles = store, nowMs = { 1_000L },
    )

    /** alice verifies first via /me → bootstraps the human OPERATOR, so the later session is a true MEMBER. */
    private suspend fun ApplicationTestBuilder.bootstrapOperatorThenMember() {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.get("/api/auth/me") { header("X-Session-Token", "sess-member") }
    }

    @Test
    fun anonymous_failsClosed_andLeaksNoRoster() = testApplication {
        val db = Files.createTempFile("cc1-anon", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        val r = client.get("/api/agents") // no credential
        assertEquals(HttpStatusCode.Unauthorized, r.status, "the roster must fail closed (401) without a credential")
        val body = r.bodyAsText()
        // The whole point of CC1: the topology must not egress to an anonymous caller.
        assertFalse(body.contains("\"backend\"") || body.contains("\"po\""),
            "the anonymous response must NOT leak any agent id — body: $body")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun operatorToken_andAgentToken_seeRoster() = testApplication {
        val db = Files.createTempFile("cc1-tok", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        val op = client.get("/api/agents") { header("Authorization", "Bearer $opToken") }
        assertEquals(HttpStatusCode.OK, op.status, "the operator token reads the roster")
        assertTrue(op.bodyAsText().contains("\"po\""), "the roster lists the agents — body: ${op.bodyAsText()}")

        // An agent token (a comm participant) also reads the roster — the tier is 'any authenticated reader'.
        val agent = client.get("/api/agents") { header("Authorization", "Bearer tok-backend") }
        assertEquals(HttpStatusCode.OK, agent.status, "an agent token reads the roster")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun humanOperatorSession_andHumanMemberSession_seeRoster() = testApplication {
        val db = Files.createTempFile("cc1-human", ".db"); val store = SqliteRoleStore(db)
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember()

        // A human OPERATOR by SESSION (no bearer) reads the roster — this is exactly the case token-only
        // `requireParticipant` would have regressed (401). The gate is `requireCommReader`, so → 200.
        val opSession = client.get("/api/agents") { header("X-Session-Token", "sess-alice") }
        assertEquals(HttpStatusCode.OK, opSession.status, "a human OPERATOR session reads the roster")

        // A human MEMBER reads the roster too — consistent with reading `/channels` (no asymmetry).
        val memberSession = client.get("/api/agents") { header("X-Session-Token", "sess-member") }
        assertEquals(HttpStatusCode.OK, memberSession.status, "a human MEMBER session reads the roster")

        store.close(); Files.deleteIfExists(db)
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
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cc1-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
