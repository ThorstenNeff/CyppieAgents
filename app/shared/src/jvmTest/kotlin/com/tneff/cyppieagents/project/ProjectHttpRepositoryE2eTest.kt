package com.tneff.cyppieagents.project

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.SwitchActiveRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-92 stub→real swap — e2e proof that [HttpProjectRepository] talks the live `/api/projects` endpoints
 * against a **real embedded Ktor server** (operator-gated, reusing the `:core` [ProjectGuard] the real server
 * uses), so the swap from [StubProjectRepository] is only a constructor change. Covers the CRUD + switch
 * round-trip, the `?deleteWorktrees=` param, the operator gate (403), and the 409 delete-safety codes
 * (`active_project_protected`/`last_project`) carrying through to the UI mapping.
 */
class ProjectHttpRepositoryE2eTest {

    private fun statusFor(code: String) = when (code) {
        "invalid_project_id" -> HttpStatusCode.BadRequest
        "project_exists", "last_project", "active_project_protected" -> HttpStatusCode.Conflict
        "project_not_found" -> HttpStatusCode.NotFound
        "operator_required" -> HttpStatusCode.Forbidden
        else -> HttpStatusCode.BadRequest
    }

    private fun err(code: String) = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, code)))

    @Test
    fun crudSwitch_roundTrips_gatesOperator_andMaps409() = runBlocking {
        val projects = mutableListOf(Project("default", "Default"))
        var active = "default"
        var lastDeleteWorktrees: String? = null

        val server = embeddedServer(Netty, port = 0) {
            routing {
                // operator gate for every mutation; reads are open.
                suspend fun io.ktor.server.application.ApplicationCall.requireOperator(): Boolean {
                    if (request.headers["Authorization"] == "Bearer op") return true
                    respondText(err("operator_required"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                    return false
                }
                get("/api/projects") {
                    call.respondText(CommJson.encodeToString(ProjectsView.serializer(), ProjectsView(active, projects.toList())), ContentType.Application.Json)
                }
                post("/api/projects") {
                    if (!call.requireOperator()) return@post
                    val req = CommJson.decodeFromString(CreateProjectRequest.serializer(), call.receiveText())
                    val code = ProjectGuard.validateCreate(projects, req)
                    if (code != null) { call.respondText(err(code), ContentType.Application.Json, statusFor(code)); return@post }
                    val created = Project(req.id, req.name)
                    projects.add(created)
                    call.respondText(CommJson.encodeToString(Project.serializer(), created), ContentType.Application.Json, HttpStatusCode.Created)
                }
                post("/api/projects/switch") {
                    if (!call.requireOperator()) return@post
                    val req = CommJson.decodeFromString(SwitchActiveRequest.serializer(), call.receiveText())
                    if (projects.none { it.id == req.projectId }) { call.respondText(err("project_not_found"), ContentType.Application.Json, HttpStatusCode.NotFound); return@post }
                    active = req.projectId
                    call.respondText(CommJson.encodeToString(ProjectsView.serializer(), ProjectsView(active, projects.toList())), ContentType.Application.Json)
                }
                put("/api/projects/{id}") {
                    if (!call.requireOperator()) return@put
                    val id = call.parameters.getOrFail("id")
                    val req = CommJson.decodeFromString(RenameProjectRequest.serializer(), call.receiveText())
                    val code = ProjectGuard.validateRename(projects, id, req.name)
                    if (code != null) { call.respondText(err(code), ContentType.Application.Json, statusFor(code)); return@put }
                    val index = projects.indexOfFirst { it.id == id }
                    val updated = projects[index].copy(name = req.name)
                    projects[index] = updated
                    call.respondText(CommJson.encodeToString(Project.serializer(), updated), ContentType.Application.Json)
                }
                delete("/api/projects/{id}") {
                    if (!call.requireOperator()) return@delete
                    val id = call.parameters.getOrFail("id")
                    lastDeleteWorktrees = call.request.queryParameters["deleteWorktrees"]
                    val code = ProjectGuard.validateDelete(projects, id, active)
                    if (code != null) { call.respondText(err(code), ContentType.Application.Json, statusFor(code)); return@delete }
                    projects.removeAll { it.id == id }
                    call.respondText("""{"worktreesRemoved":0}""", ContentType.Application.Json) // receipt body (ignored)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val base = "http://127.0.0.1:$port"
            val client = HttpClient(CIO)
            try {
                val repo = HttpProjectRepository(client, base, token = "op")

                // create → list (active pointer + registry round-trip).
                val created = repo.create(CreateProjectRequest("alpha", "Alpha"))
                assertEquals("alpha", created.id)
                assertEquals(setOf("default", "alpha"), repo.list().projects.map { it.id }.toSet())
                assertEquals("default", repo.list().activeProjectId)

                // switch flips the active pointer (the view re-resolves).
                assertEquals("alpha", repo.switchActive("alpha").activeProjectId)

                // rename (id stable).
                assertEquals("Renamed", repo.rename("alpha", RenameProjectRequest("Renamed")).name)

                // 409 active_project_protected → carries the reason code to the UI mapping.
                val activeBlocked = assertFails { repo.delete("alpha", deleteWorktrees = false) } // alpha is now active
                assertIs<ProjectException>(activeBlocked)
                assertEquals("active_project_protected", activeBlocked.code)

                // switch back to default, delete alpha with the worktree-delete path → param sent.
                repo.switchActive("default")
                repo.delete("alpha", deleteWorktrees = true)
                assertEquals("true", lastDeleteWorktrees)
                assertEquals(setOf("default"), repo.list().projects.map { it.id }.toSet())

                // 409 last_project (only one left, and it's active).
                val lastBlocked = assertFails { repo.delete("default", deleteWorktrees = false) }
                assertIs<ProjectException>(lastBlocked)
                assertEquals("last_project", lastBlocked.code)

                // operator gate: a repo without the operator token is 403-mapped on a mutation.
                val ungated = HttpProjectRepository(client, base, token = "")
                val gateErr = assertFails { ungated.create(CreateProjectRequest("beta", "Beta")) }
                assertIs<ProjectException>(gateErr)
                assertEquals("operator_required", gateErr.code)
                // The read path still works without the gate.
                assertTrue(ungated.list().projects.isNotEmpty())
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
