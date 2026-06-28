package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.ProjectDeleter
import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.SwitchActiveRequest
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.projectRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * HTTP contract for the multi-project lifecycle (S13 / CYP-91). Reviewer focus: every operation is
 * operator-gated and fail-closed, GET egresses ONLY the `:core` DTO set (no internal registry leak —
 * the CYP-81 egress class), the delete-safety codes surface with the right HTTP status, and the
 * worktree cascade is opt-in.
 */
class ProjectRoutesTest {

    /** Fresh registry (default active + beta) + a real deleter per test, since mutations alter state. */
    private fun fixture(single: Boolean = false): Pair<ProjectRegistry, ProjectDeleter> {
        val registry = ProjectRegistry(file = null, seedProjectId = "default", seedProjectName = "Default")
        if (!single) registry.create(CreateProjectRequest("beta", "Beta"))
        val gitRoot = Files.createTempDirectory("proj-routes").toFile()
        val config = ProjectConfigStore(null, RepoConfig("u", "main"), Secrets(mapOf("t" to "po"), "op", apiKey = null))
        val deleter = ProjectDeleter(registry, config, InMemoryEventSink(SystemTimeSource()), WorktreeManager(FakeGit(), gitRoot))
        return registry to deleter
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.app(reg: ProjectRegistry, del: ProjectDeleter) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            routing { projectRoutes(reg, del, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op")) }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    // ---- GET: operator-gated, egresses only the DTO set ----

    @Test fun get_noToken_401() = testApplication {
        val (r, d) = fixture(); app(r, d)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/projects").status)
    }

    @Test fun get_operator_returnsExactlyTheProjectsViewDto() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().get("/api/projects") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, resp.status)
        // Egress lock (CYP-81 class): the body is byte-for-byte the ProjectsView DTO — no internal
        // registry fields (no file path, lock, LinkedHashMap) can ever leak through.
        val expected = CommJson.encodeToString(ProjectsView("default", listOf(Project("default", "Default"), Project("beta", "Beta"))))
        assertEquals(expected, resp.bodyAsText())
        val body = resp.body<ProjectsView>()
        assertEquals("default", body.activeProjectId)
        assertEquals(listOf("default", "beta"), body.projects.map { it.id })
    }

    // ---- create ----

    @Test fun post_noToken_401() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects") {
            contentType(ContentType.Application.Json); setBody(CreateProjectRequest("gamma", "Gamma"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertFalse(r.exists("gamma"), "fail-closed: no project created without auth")
    }

    @Test fun post_participant_403_operatorRequired() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects") {
            bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(CreateProjectRequest("gamma", "Gamma"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals("operator_required", resp.body<ApiErrorBody>().error.code)
        assertFalse(r.exists("gamma"), "fail-closed: participant cannot create")
    }

    @Test fun post_operator_creates201() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(CreateProjectRequest("gamma", "Gamma"))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        assertEquals("gamma", resp.body<Project>().id)
    }

    @Test fun post_operator_duplicate_409() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(CreateProjectRequest("beta", "X"))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertEquals("project_exists", resp.body<ApiErrorBody>().error.code)
    }

    @Test fun post_operator_invalidId_400() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(CreateProjectRequest("a/b", "X"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals("invalid_project_id", resp.body<ApiErrorBody>().error.code)
    }

    // ---- rename ----

    @Test fun put_operator_renames() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().put("/api/projects/beta") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(RenameProjectRequest("Beta v2"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("Beta v2", resp.body<Project>().name)
    }

    @Test fun put_participant_403() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().put("/api/projects/beta") {
            bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(RenameProjectRequest("X"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test fun put_unknown_404() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().put("/api/projects/ghost") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(RenameProjectRequest("X"))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertEquals("project_not_found", resp.body<ApiErrorBody>().error.code)
    }

    // ---- switch active (pointer only; POST /switch — never shadows rename) ----

    @Test fun switch_operator_flipsPointer() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects/switch") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("beta"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("beta", resp.body<ProjectsView>().activeProjectId)
        assertEquals("beta", r.activeProjectId())
    }

    @Test fun switch_noToken_401_pointerUnchanged() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects/switch") {
            contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("beta"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertEquals("default", r.activeProjectId(), "fail-closed: pointer not flipped without auth")
    }

    @Test fun switch_unknown_404() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().post("/api/projects/switch") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("ghost"))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test fun renameProjectNamedActive_reachesRenameNotSwitch() = testApplication {
        // N2 (route-shadowing edge): a project whose id is literally `active` must be renamable. Since
        // switch is POST /switch (not PUT /active), PUT /api/projects/active hits the rename route.
        val (r, d) = fixture(); app(r, d)
        r.create(CreateProjectRequest("active", "Active")) // a real project id == the old switch segment
        val resp = jsonClient().put("/api/projects/active") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(RenameProjectRequest("Renamed"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("Renamed", resp.body<Project>().name, "PUT /api/projects/active renames the project, not switches")
        assertEquals("default", r.activeProjectId(), "rename must not have flipped the active pointer")
    }

    // ---- delete (fail-closed + safety codes + opt-in worktrees) ----

    @Test fun delete_noToken_401_nothingRemoved() = testApplication {
        val (r, d) = fixture(); app(r, d)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().delete("/api/projects/beta").status)
        assertEquals(listOf("default", "beta"), r.view().projects.map { it.id })
    }

    @Test fun delete_participant_403() = testApplication {
        val (r, d) = fixture(); app(r, d)
        assertEquals(HttpStatusCode.Forbidden, jsonClient().delete("/api/projects/beta") { bearerAuth("tok-fe") }.status)
    }

    @Test fun delete_active_409_activeProtected() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().delete("/api/projects/default") { bearerAuth("tok-op") } // default is active
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertEquals("active_project_protected", resp.body<ApiErrorBody>().error.code)
    }

    @Test fun delete_lastProject_409() = testApplication {
        val (r, d) = fixture(single = true); app(r, d)
        val resp = jsonClient().delete("/api/projects/default") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.Conflict, resp.status)
        assertEquals("last_project", resp.body<ApiErrorBody>().error.code)
    }

    @Test fun delete_operator_nonActive_200_keepsWorktreesByDefault() = testApplication {
        val (r, d) = fixture(); app(r, d)
        val resp = jsonClient().delete("/api/projects/beta") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(0, resp.body<com.tneff.cyppieagents.boot.ProjectDeleteReceipt>().worktreesRemoved, "opt-in: worktrees kept by default")
        assertFalse(r.exists("beta"), "beta removed")
    }
}
