package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.RepoReprovision
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.model.ReprovisionPreview
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-466 — `GET /api/config/repo/reprovision-preview`: OPERATOR-tier (a non-operator is 403, unauth 401), and
 * the response carries the LIVE per-agent at-risk work + whether a re-provision is pending. The worktree manager
 * is backed by a fake git with one dirty worktree, so the operator sees exactly that agent.
 */
class ReprovisionPreviewRoutesTest {

    private val reg = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")

    /** A WorktreeManager whose `default` project has one dirty worktree ("alice") and one clean ("bob"). */
    private fun worktrees(): WorktreeManager {
        val root = Files.createTempDirectory("reprovision-preview").toFile()
        listOf("alice", "bob").forEach { File(root, "projects/default/$it").mkdirs() }
        val git = object : CommandRunner {
            override fun run(command: List<String>, cwd: File): CommandResult = when {
                command.contains("status") -> CommandResult(0, if (cwd.name == "alice") " M f.kt" else "")
                else -> CommandResult(0, "")
            }
        }
        return WorktreeManager(git, root)
    }

    private fun ApplicationTestBuilder.app(pending: Boolean) {
        val deps = AuthDeps(reg)
        val reprovision = RepoReprovision().apply { if (pending) markStale("default", discardUnpushed = false) }
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing {
                reprovisionPreviewRoutes({ worktrees() }, { "default" }, reg, deps, reprovision = reprovision)
            }
        }
    }

    private fun ApplicationTestBuilder.httpClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun operator_seesPending_andTheLiveAtRiskAgent() = testApplication {
        app(pending = true)
        val res = httpClient().get("/api/config/repo/reprovision-preview") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, res.status)
        val preview: ReprovisionPreview = res.body()
        assertTrue(preview.reprovisionPending, "a repo change is staged → pending")
        assertEquals(listOf("alice"), preview.atRisk.map { it.worktree }, "only the dirty worktree is at risk (bob is clean)")
        assertTrue(preview.atRisk.single().uncommitted, "alice's risk is an uncommitted tree")
    }

    @Test
    fun notPending_reportsFalse_stillListsLiveAtRisk() = testApplication {
        app(pending = false)
        val preview: ReprovisionPreview = httpClient().get("/api/config/repo/reprovision-preview") { bearerAuth("tok-op") }.body()
        assertTrue(!preview.reprovisionPending, "no repo change staged → not pending")
        assertEquals(listOf("alice"), preview.atRisk.map { it.worktree }, "at-risk work is computed LIVE regardless of pending")
    }

    @Test
    fun unauthenticated_is401() = testApplication {
        app(pending = true)
        assertEquals(HttpStatusCode.Unauthorized, httpClient().get("/api/config/repo/reprovision-preview").status)
    }

    @Test
    fun nonOperator_is403() = testApplication {
        app(pending = true)
        assertEquals(
            HttpStatusCode.Forbidden,
            httpClient().get("/api/config/repo/reprovision-preview") { bearerAuth("tok-be") }.status,
            "an agent (non-operator) token is forbidden — this is operator-tier project config",
        )
    }
}
