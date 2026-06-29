package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.ProjectDeleteReceipt
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SwitchActiveRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E Journey J1 (CYP-107) — Multi-Project Lifecycle: create / rename / switch / delete-cascade over the
 * REAL embedded platform (CYP-106 harness). Every assertion is proven through the real HTTP path and is
 * non-vacuum (mutation read back). Fail-closed axes: operator-gating (participant→403), SAFE_ID
 * path-traversal reject (400), active/last-project delete protection (409, tears down nothing),
 * no-cross-project cascade (delete B never touches A). See `test/E2E-TEST-PLAN.md` §2/§8.
 */
class J1LifecycleE2eTest {

    private fun singleProject() = e2ePlatform(
        listOf(SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend")))),
    )

    private fun twoProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("backend")), apiKey = "beta-secret-key-1234"),
        ),
    )

    private suspend fun E2ePlatform.projectsView(): ProjectsView =
        asOperator().use { it.get("$baseUrl/api/projects").body() }

    private suspend fun E2ePlatform.activeEventProjectIds(): List<String> =
        asOperator().use { it.get("$baseUrl/api/events").body<EventPage>().events.map { e -> e.projectId } }

    @Test
    fun create_operator_addsB_201_listShowsBoth() = runBlocking {
        singleProject().use { p ->
            p.asOperator().use { c ->
                val r = c.post("${p.baseUrl}/api/projects") {
                    contentType(ContentType.Application.Json); setBody(CreateProjectRequest("beta", "Beta"))
                }
                assertEquals(HttpStatusCode.Created, r.status)
                assertEquals("beta", r.body<Project>().id)
            }
            assertEquals(setOf("alpha", "beta"), p.projectsView().projects.map { it.id }.toSet())
        }
    }

    @Test
    fun create_participant_403_nothingAdded() = runBlocking {
        singleProject().use { p ->
            p.asAgent("frontend").use { c ->
                val r = c.post("${p.baseUrl}/api/projects") {
                    contentType(ContentType.Application.Json); setBody(CreateProjectRequest("beta", "Beta"))
                }
                assertEquals(HttpStatusCode.Forbidden, r.status)
            }
            assertEquals(listOf("alpha"), p.projectsView().projects.map { it.id }, "no project added by a non-operator")
        }
    }

    @Test
    fun create_badId_400_invalidProjectId_pathTraversalRejected() = runBlocking {
        singleProject().use { p ->
            for (bad in listOf("../x", "a/b", "a b", "")) {
                p.asOperator().use { c ->
                    val r = c.post("${p.baseUrl}/api/projects") {
                        contentType(ContentType.Application.Json); setBody(CreateProjectRequest(bad, "X"))
                    }
                    assertEquals(HttpStatusCode.BadRequest, r.status, "id '$bad' must be rejected (SAFE_ID)")
                    assertEquals("invalid_project_id", r.body<ApiErrorBody>().error.code)
                }
            }
            assertEquals(listOf("alpha"), p.projectsView().projects.map { it.id }, "no malformed id created")
        }
    }

    @Test
    fun create_duplicate_409_projectExists() = runBlocking {
        twoProjects().use { p ->
            p.asOperator().use { c ->
                val r = c.post("${p.baseUrl}/api/projects") {
                    contentType(ContentType.Application.Json); setBody(CreateProjectRequest("beta", "Beta"))
                }
                assertEquals(HttpStatusCode.Conflict, r.status)
                assertEquals("project_exists", r.body<ApiErrorBody>().error.code)
            }
        }
    }

    @Test
    fun rename_operator_ok_participant_403() = runBlocking {
        twoProjects().use { p ->
            p.asOperator().use { c ->
                val r = c.put("${p.baseUrl}/api/projects/beta") {
                    contentType(ContentType.Application.Json); setBody(RenameProjectRequest("Beta Renamed"))
                }
                assertEquals(HttpStatusCode.OK, r.status)
                assertEquals("Beta Renamed", r.body<Project>().name)
            }
            p.asAgent("frontend").use { c ->
                val r = c.put("${p.baseUrl}/api/projects/beta") {
                    contentType(ContentType.Application.Json); setBody(RenameProjectRequest("Hacked"))
                }
                assertEquals(HttpStatusCode.Forbidden, r.status)
            }
            assertEquals("Beta Renamed", p.projectsView().projects.first { it.id == "beta" }.name, "agent rename did not take")
        }
    }

    @Test
    fun switch_operator_flipsActive_participant_403_activeUnchanged() = runBlocking {
        twoProjects().use { p ->
            p.asOperator().use { c ->
                val r = c.post("${p.baseUrl}/api/projects/switch") {
                    contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("beta"))
                }
                assertEquals(HttpStatusCode.OK, r.status)
                assertEquals("beta", r.body<ProjectsView>().activeProjectId)
            }
            p.asAgent("frontend").use { c ->
                val r = c.post("${p.baseUrl}/api/projects/switch") {
                    contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("alpha"))
                }
                assertEquals(HttpStatusCode.Forbidden, r.status)
            }
            assertEquals("beta", p.projectsView().activeProjectId, "rejected agent switch left the pointer at beta")
        }
    }

    @Test
    fun deleteB_nonActive_op_cascadesOnlyB_AuntouchedNoCrossProject() = runBlocking {
        twoProjects().use { p ->
            p.injectDroppedEvent("alpha")
            p.injectDroppedEvent("beta")
            val alphaBefore = p.activeEventProjectIds() // active = alpha → only alpha's events
            assertTrue(alphaBefore.isNotEmpty(), "alpha has at least the injected event")
            assertTrue(alphaBefore.all { it == "alpha" })

            val receipt: ProjectDeleteReceipt = p.asOperator().use {
                it.delete("${p.baseUrl}/api/projects/beta").body()
            }
            assertEquals("beta", receipt.projectId)
            assertTrue(receipt.configRemoved, "beta's seeded apiKey config cascaded")
            assertTrue(receipt.eventsRemoved >= 1, "beta's events cascaded")

            // no-cross-project: A's events untouched, beta gone from the registry
            assertEquals(alphaBefore.size, p.activeEventProjectIds().size, "alpha events untouched by beta delete")
            assertEquals(listOf("alpha"), p.projectsView().projects.map { it.id })
        }
    }

    @Test
    fun deleteB_noWorktreeFlag_keepsWorktrees() = runBlocking {
        twoProjects().use { p ->
            val receipt: ProjectDeleteReceipt = p.asOperator().use {
                it.delete("${p.baseUrl}/api/projects/beta").body()
            }
            assertEquals(0, receipt.worktreesRemoved, "default deleteWorktrees=false keeps worktrees (config+events still cascade)")
            assertTrue(receipt.configRemoved)
        }
    }

    @Test
    fun delete_activeProject_409_protected_tearsNothing() = runBlocking {
        twoProjects().use { p ->
            p.asOperator().use { c ->
                val r = c.delete("${p.baseUrl}/api/projects/alpha") // alpha is active
                assertEquals(HttpStatusCode.Conflict, r.status)
                assertEquals("active_project_protected", r.body<ApiErrorBody>().error.code)
            }
            assertTrue(p.projectsView().projects.any { it.id == "alpha" }, "active project still present")
        }
    }

    @Test
    fun delete_lastProject_409_tearsNothing() = runBlocking {
        singleProject().use { p ->
            p.asOperator().use { c ->
                val r = c.delete("${p.baseUrl}/api/projects/alpha")
                assertEquals(HttpStatusCode.Conflict, r.status)
                assertEquals("last_project", r.body<ApiErrorBody>().error.code)
            }
            assertEquals(listOf("alpha"), p.projectsView().projects.map { it.id })
        }
    }

    @Test
    fun delete_participant_403_nothingRemoved() = runBlocking {
        twoProjects().use { p ->
            p.asAgent("frontend").use { c ->
                val r = c.delete("${p.baseUrl}/api/projects/beta")
                assertEquals(HttpStatusCode.Forbidden, r.status)
            }
            assertTrue(p.projectsView().projects.any { it.id == "beta" }, "beta intact after rejected delete")
        }
    }
}
