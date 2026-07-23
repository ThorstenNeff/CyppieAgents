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
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
 * CYP-186 (S18 / Token-Disposition C) — the operator token stays an OR at the gate; C makes it **attributed**
 * (C.1) and **deploy-disablable** (C.2). C.1: every OPERATOR mutation is audited to the **OPERATOR-only** sink
 * with the principal (`operator-token` vs `human:<id>`, never generic), **no body/secret**; a MEMBER can't read
 * the audit and it's structurally absent from the MEMBER event-log. C.2: the kill-switch is deploy-set and
 * **never-lock-out** (effective only once a role-OPERATOR exists).
 */
class TokenDispositionTest {

    private val opToken = "tok-op"

    private fun idp() = FakeIdentityProvider(
        mapOf(
            "sess-alice" to ResolvedIdentity("alice-op", verified = true, aal2 = true),
            "sess-carol" to ResolvedIdentity("carol-mem", verified = true),
        ),
    )

    private fun deps(store: SqliteRoleStore, audit: AuditSink = NoOpAuditSink, disabled: Boolean = false) =
        AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken), idp(), store, { 1_000L }, audit, disabled, browserOperatorPostureEnabled = true)

    private suspend fun ApplicationTestBuilder.bootstrap(vararg sessions: String) {
        sessions.forEach { client.get("/api/auth/me") { header("X-Session-Token", it) } }
    }

    // ---- C.1 audit ----

    @Test
    fun operatorTokenMutation_auditedAsOperatorToken_noSecretBody() = testApplication {
        val db = Files.createTempFile("c1-op", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op"); val audit = InMemoryAuditSink()
        application { installPlatform(bootFake(), deps(store, audit), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        // The apikey PUT body IS the secret; the guard audits BEFORE the handler → the record must not carry it.
        client.put("/api/config/apikey") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json)
            setBody("""{"apiKey":"sk-SECRETVALUE-9999"}""")
        }
        val recs = audit.recent(10)
        assertTrue(recs.any { it.actor == "operator-token" && it.method == "PUT" && it.path == "/api/config/apikey" },
            "the operator-token mutation must be audited by path — recs: $recs")
        assertTrue(recs.none { it.actor.contains("SECRETVALUE") || it.path.contains("SECRETVALUE") || it.method.contains("SECRETVALUE") },
            "the audit must NEVER carry the secret body — recs: $recs")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun humanOperatorMutation_auditedByIdentityId() = testApplication {
        val db = Files.createTempFile("c1-hu", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op"); val audit = InMemoryAuditSink()
        application { installPlatform(bootFake(), deps(store, audit), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrap("sess-alice") // alice-op → OPERATOR (pinned, CYP-196)

        // X-Session-Token is CSRF-immune (RC5); a human OPERATOR PUT is attributed by identityId, never "operator".
        client.put("/api/config/apikey") {
            header("X-Session-Token", "sess-alice"); contentType(ContentType.Application.Json); setBody("""{"apiKey":"sk-x"}""")
        }
        val recs = audit.recent(10)
        assertTrue(recs.any { it.actor == "human:alice-op" }, "human operator attributed by identityId — recs: $recs")
        assertTrue(recs.none { it.actor == "operator" }, "never a generic 'operator' actor — recs: $recs")

        store.close(); Files.deleteIfExists(db)
    }

    @Test
    fun safeGet_notAudited_and_memberCannotReadAudit_norSeesItInEventLog() = testApplication {
        val db = Files.createTempFile("c1-iso", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op"); val audit = InMemoryAuditSink()
        application { installPlatform(bootFake(), deps(store, audit), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        bootstrap("sess-alice", "sess-carol") // alice-op OPERATOR (pinned), carol MEMBER

        // A safe GET under the operator gate is NOT audited (mutations only).
        client.get("/api/workspace/members") { header("Authorization", "Bearer $opToken") }
        assertTrue(audit.recent(10).isEmpty(), "a safe GET must not be audited")

        // An operator mutation, then: MEMBER can't read /api/audit (403), and the mutation is NOT in the
        // MEMBER-readable event-log (structural isolation — a separate sink).
        client.put("/api/config/apikey") { header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody("""{"apiKey":"sk-y"}""") }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/audit") { header("X-Session-Token", "sess-carol") }.status)
        val memberEvents = client.get("/api/events") { header("X-Session-Token", "sess-carol") }.bodyAsText()
        assertFalse(memberEvents.contains("operator-token"), "audit must be structurally ABSENT from the MEMBER event-log — was: $memberEvents")

        store.close(); Files.deleteIfExists(db)
    }

    // ---- C.2 kill-switch + never-lock-out ----

    @Test
    fun killSwitch_beforeFirstOperator_tokenStillAuthorizes_thenInertOnceRoleOperatorExists() = testApplication {
        val db = Files.createTempFile("c2", ".db"); val store = SqliteRoleStore(db, bootstrapOperatorId = "alice-op")
        application { installPlatform(bootFake(), deps(store, disabled = true), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()

        // disabled=true BUT no role-OPERATOR yet → the token MUST still authorize (never lock out the platform).
        assertEquals(HttpStatusCode.OK, client.get("/api/workspace/members") { header("Authorization", "Bearer $opToken") }.status)

        // A human verifies → bootstraps role-OPERATOR → NOW the kill-switch is effective → the same token is inert.
        bootstrap("sess-alice")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/workspace/members") { header("Authorization", "Bearer $opToken") }.status,
            "once a role-OPERATOR exists, the disabled operator token is inert (403)")

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
        val gitRoot = Files.createTempDirectory("tokendisp-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }
}
