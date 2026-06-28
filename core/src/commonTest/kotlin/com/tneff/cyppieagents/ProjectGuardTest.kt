package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pure multi-project lifecycle invariants (S13 / CYP-91). Platform-neutral so the server registry
 * and the client UI share the EXACT rules — fail-closed, and the two delete-safety guardrails the PO
 * pinned: the last project and the active project are never deletable.
 *
 * Each assertion isolates one guard line so it is mutation-proof: reverting exactly that line turns
 * the matching case red without touching the others.
 */
class ProjectGuardTest {

    private val projects = listOf(
        Project("default", "Default"),
        Project("beta", "Beta"),
    )

    private fun create(id: String, name: String = "X") = CreateProjectRequest(id, name)

    // ---- create ----

    @Test fun create_ok() = assertNull(ProjectGuard.validateCreate(projects, create("gamma")))

    @Test fun create_blankOrBadIdOrName_invalidProjectId() {
        assertEquals("invalid_project_id", ProjectGuard.validateCreate(projects, create("")))
        assertEquals("invalid_project_id", ProjectGuard.validateCreate(projects, CreateProjectRequest("x", "")))
        // path/ref-unsafe ids are rejected (id → projects/<id>/ dir, channel projectId-stamp, event key)
        assertEquals("invalid_project_id", ProjectGuard.validateCreate(projects, create("../escape")))
        assertEquals("invalid_project_id", ProjectGuard.validateCreate(projects, create("a b")))
        assertEquals("invalid_project_id", ProjectGuard.validateCreate(projects, create("a/b")))
    }

    @Test fun create_duplicateId_projectExists() =
        assertEquals("project_exists", ProjectGuard.validateCreate(projects, create("beta")))

    // ---- rename ----

    @Test fun rename_ok() = assertNull(ProjectGuard.validateRename(projects, "beta", "Beta v2"))

    @Test fun rename_blankName_invalidProjectId() =
        assertEquals("invalid_project_id", ProjectGuard.validateRename(projects, "beta", "  "))

    @Test fun rename_unknown_projectNotFound() =
        assertEquals("project_not_found", ProjectGuard.validateRename(projects, "ghost", "X"))

    // ---- delete ----

    @Test fun delete_nonActiveWithOthers_ok() =
        assertNull(ProjectGuard.validateDelete(projects, "beta", activeProjectId = "default"))

    @Test fun delete_unknown_projectNotFound() =
        assertEquals("project_not_found", ProjectGuard.validateDelete(projects, "ghost", activeProjectId = "default"))

    @Test fun delete_active_activeProjectProtected() =
        assertEquals("active_project_protected", ProjectGuard.validateDelete(projects, "default", activeProjectId = "default"))

    @Test fun delete_lastProject_lastProject() {
        val one = listOf(Project("default", "Default"))
        // The single remaining project is necessarily the active one; last_project is the clearer reason.
        assertEquals("last_project", ProjectGuard.validateDelete(one, "default", activeProjectId = "default"))
    }
}
