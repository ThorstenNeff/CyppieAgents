package com.tneff.cyppieagents.auth

import com.tneff.cyppieagents.CommJson
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
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.ClaudeMdUpdate
import com.tneff.cyppieagents.model.ClaudeMdView
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-320 (Bug/High) — the by-id edit-prefill GETs must be READ-TIER, not token-only. On staging a browser
 * authenticated by its **Kratos session cookie** (no bearer) got a live roster (`GET /api/agents` is
 * session-readable, CC1/CYP-179) but the "adjust agent" panel's two by-id GETs went dead: the token-only
 * `requireParticipant` 401'd the session → `GET /api/agents/{id}` didn't resolve (the CYP-315 worktree path
 * blanked) and `GET /api/agents/{id}/claude-md` THREW ("Laden fehlgeschlagen") instead of the calm
 * `exists=…` read. The fix moves both to `requireCommReader` — the SAME reader tier as the roster / the
 * avatar serve right beside them — so a verified human session reaches them, WITHOUT removing the bearer
 * path (operator/agent token still 200) and WITHOUT widening the operator-gated HARD-overwrite POST.
 *
 * Boots the REAL platform (real routing/auth/AgentManagement) with an injected fake idp — alice first-verified
 * → OPERATOR, carol → MEMBER — and a real CLAUDE.md planted in the backend worktree (faithful to "the file
 * IS there" on staging). The load-bearing tooth: a SESSION read → 200 (not 401). Mutation M (revert both to
 * `requireParticipant`) reds `sessionOperator_reads*_200`.
 */
class Cyp320ByIdGetSessionReadTest {

    private val opToken = "tok-op"
    private val agentToken = "tok-backend"
    private val claudeMdBody = "# Backend\nYou are the backend developer.\n"

    private fun authDeps(store: SqliteRoleStore) = AuthDeps(
        tokens = TokenRegistry(emptyMap(), operatorToken = opToken),
        idp = FakeIdentityProvider(
            mapOf(
                "sess-alice" to ResolvedIdentity("alice-op", verified = true), // first-verified → OPERATOR
                "sess-carol" to ResolvedIdentity("carol-member", verified = true), // later → MEMBER
            ),
        ),
        roles = store, nowMs = { 1_000L },
    )

    /** alice verifies first via /me → bootstraps the human OPERATOR, so carol's session is a true MEMBER. */
    private suspend fun ApplicationTestBuilder.bootstrapOperatorThenMember() {
        client.get("/api/auth/me") { header("X-Session-Token", "sess-alice") }
        client.get("/api/auth/me") { header("X-Session-Token", "sess-carol") }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    // ---- the CYP-320 tooth: a SESSION-authenticated read → 200 (not the 401 bug) ----

    @Test
    fun sessionOperator_readsDetailAndClaudeMd_200_notThe401Bug() = testApplication {
        val db = Files.createTempFile("cyp320-op", ".db"); val store = SqliteRoleStore(db)
        val (booted, gitRoot) = bootFake()
        plantClaudeMd(gitRoot, "backend")
        application { installPlatform(booted, authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); bootstrapOperatorThenMember()
        val c = jsonClient()

        // GET /api/agents/{id} — a human OPERATOR by SESSION (no bearer) resolves the detail (the token-only
        // requireParticipant would 401 here; that was the dead-field bug). The CYP-315 worktree path is present.
        val detailR = c.get("/api/agents/backend") { header("X-Session-Token", "sess-alice") }
        assertEquals(HttpStatusCode.OK, detailR.status, "a human OPERATOR session must resolve the by-id detail (not 401)")
        val detail = detailR.body<AgentDetail>()
        assertEquals("backend", detail.id)
        assertNotNull(detail.worktreePath, "the CYP-315 worktree path renders for a session read (was blank on the 401)")

        // GET /api/agents/{id}/claude-md — the calm read, NOT the "Laden fehlgeschlagen" throw. exists=true, content back.
        val mdR = c.get("/api/agents/backend/claude-md") { header("X-Session-Token", "sess-alice") }
        assertEquals(HttpStatusCode.OK, mdR.status, "a human OPERATOR session must read the live CLAUDE.md (not 401)")
        val md = mdR.body<ClaudeMdView>()
        assertTrue(md.exists, "the planted CLAUDE.md is present → exists=true (the calm read, not a throw)")
        assertEquals(claudeMdBody, md.content, "the live worktree CLAUDE.md content is returned")

        store.close(); Files.deleteIfExists(db); gitRoot.deleteRecursively()
    }

    @Test
    fun sessionMember_readsBoth_200_sameReaderTier() = testApplication {
        val db = Files.createTempFile("cyp320-mem", ".db"); val store = SqliteRoleStore(db)
        val (booted, gitRoot) = bootFake()
        plantClaudeMd(gitRoot, "backend")
        application { installPlatform(booted, authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); bootstrapOperatorThenMember()
        val c = jsonClient()

        // A human MEMBER reads both by-id GETs too — the SAME participant-read tier as the roster / avatar reads
        // (no asymmetry). No over-grant: this is the read tier, not the operator-gated write below.
        assertEquals(HttpStatusCode.OK, c.get("/api/agents/backend") { header("X-Session-Token", "sess-carol") }.status,
            "a human MEMBER session reads the by-id detail (reader tier, like the roster)")
        assertEquals(HttpStatusCode.OK, c.get("/api/agents/backend/claude-md") { header("X-Session-Token", "sess-carol") }.status,
            "a human MEMBER session reads the CLAUDE.md (reader tier)")

        store.close(); Files.deleteIfExists(db); gitRoot.deleteRecursively()
    }

    // ---- invariant #1: the bearer read is PRESERVED (operator/agent token still 200) ----

    @Test
    fun bearerRead_operatorAndAgentToken_still200() = testApplication {
        val db = Files.createTempFile("cyp320-bearer", ".db"); val store = SqliteRoleStore(db)
        val (booted, gitRoot) = bootFake()
        plantClaudeMd(gitRoot, "backend")
        application { installPlatform(booted, authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication()
        val c = jsonClient()

        for (tok in listOf(opToken, agentToken)) {
            assertEquals(HttpStatusCode.OK, c.get("/api/agents/backend") { header("Authorization", "Bearer $tok") }.status,
                "the bearer read of the detail must be preserved (token=$tok)")
            assertEquals(HttpStatusCode.OK, c.get("/api/agents/backend/claude-md") { header("Authorization", "Bearer $tok") }.status,
                "the bearer read of the CLAUDE.md must be preserved (token=$tok)")
        }

        store.close(); Files.deleteIfExists(db); gitRoot.deleteRecursively()
    }

    // ---- invariant #3: the HARD-overwrite POST stays operator-gated (only the GETs are read-tier) ----

    @Test
    fun claudeMdPost_staysOperatorGated_memberAndAnonDenied() = testApplication {
        val db = Files.createTempFile("cyp320-post", ".db"); val store = SqliteRoleStore(db)
        val (booted, gitRoot) = bootFake()
        plantClaudeMd(gitRoot, "backend")
        application { installPlatform(booted, authDeps(store), settingsClient = KratosSettingsClient("http://localhost:1")) }
        startApplication(); bootstrapOperatorThenMember()
        val c = jsonClient()

        // A MEMBER session may READ the CLAUDE.md but must NOT overwrite it — the POST is structurally operator-gated.
        val memberPost = c.post("/api/agents/backend/claude-md") {
            header("X-Session-Token", "sess-carol"); contentType(ContentType.Application.Json); setBody(ClaudeMdUpdate("hijacked", null))
        }
        assertEquals(HttpStatusCode.Forbidden, memberPost.status, "a MEMBER cannot HARD-overwrite the CLAUDE.md (operator-only)")

        // No credential → 401 (fail-closed, unparsed).
        val anonPost = c.post("/api/agents/backend/claude-md") {
            contentType(ContentType.Application.Json); setBody(ClaudeMdUpdate("hijacked", null))
        }
        assertEquals(HttpStatusCode.Unauthorized, anonPost.status, "no credential cannot HARD-overwrite the CLAUDE.md")

        // The read still shows the ORIGINAL — neither denied POST mutated the file.
        val md = c.get("/api/agents/backend/claude-md") { header("Authorization", "Bearer $opToken") }.body<ClaudeMdView>()
        assertEquals(claudeMdBody, md.content, "the denied POSTs changed nothing (fail-closed)")

        store.close(); Files.deleteIfExists(db); gitRoot.deleteRecursively()
    }

    // ---- fake boot (mirrors Cc1AgentsGateTest.bootFake), exposing gitRoot so a real CLAUDE.md can be planted ----

    private fun plantClaudeMd(gitRoot: File, worktree: String) {
        val f = File(gitRoot, "projects/default/$worktree/CLAUDE.md")
        f.parentFile.mkdirs()
        f.writeText(claudeMdBody)
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): Pair<BootedPlatform, File> {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf(agentToken to "backend"), operatorToken = opToken, apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp320-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        val booted = BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
        return booted to gitRoot
    }
}
