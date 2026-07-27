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
 * CYP-186 (S18 kick-off / BE3a) — the **OPERATOR-only workspace roster**. `GET /api/workspace/members` lists
 * `{identityId, tier}` for the OPERATOR; a MEMBER is **403 content-free** (guardrail iii — no contact-dump);
 * no credential → 401. The response carries **no email / no secret** (content-free). displayName is deferred
 * (null) pending the admin-API seam + a name trait.
 */
class WorkspaceRosterTest {

    private val opToken = "tok-op"

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken, loopbackPosture = true),
        idp = FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true),
                "sess-member" to ResolvedIdentity("member-id", verified = true),
            ),
        ),
        roles = store, nowMs = { 1_000L },
    )

    private suspend fun ApplicationTestBuilder.bootstrapOperatorThenMember() {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }  // CYP-196: pinned alice-op → OPERATOR
        client.get("/api/auth/me") { header("X-Session-Token", "sess-member") } // any other verified → MEMBER
    }

    @Test
    fun roster_operatorSeesTiers_memberIs403_noCredential401_contentFree() = testApplication {
        val db = Files.createTempFile("be3a-roster", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op") // CYP-196: pinned OPERATOR
        application { installPlatform(bootFake(), authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrapOperatorThenMember()

        // No credential → 401 (fail-closed).
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/workspace/members").status)

        // MEMBER → 403, content-free (no roster egress to a member — guardrail iii).
        val memberResp = client.get("/api/workspace/members") { header("X-Session-Token", "sess-member") }
        assertEquals(HttpStatusCode.Forbidden, memberResp.status)
        assertFalse(memberResp.bodyAsText().contains("alice-op"), "a MEMBER must see NO roster content")
        assertFalse(memberResp.bodyAsText().contains("member-id"), "a MEMBER must see NO roster content")

        // OPERATOR → the roster with tiers.
        val opResp = client.get("/api/workspace/members") { header("Authorization", "Bearer $opToken") }
        assertEquals(HttpStatusCode.OK, opResp.status)
        val body = opResp.bodyAsText()
        assertTrue(body.contains("alice-op") && body.contains("OPERATOR"), "operator row present — was: $body")
        assertTrue(body.contains("member-id") && body.contains("MEMBER"), "member row present — was: $body")
        // Content-free: never an email / secret.
        assertFalse(body.contains("@"), "the roster must never carry an email — was: $body")

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
        val gitRoot = Files.createTempDirectory("be3a-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
