package com.tneff.cyppieagents.project

import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest

/**
 * In-memory [ProjectRepository] for ungated development and hermetic tests (S13) until the registry seam
 * lands — then the live REST client replaces this with no UI change. Enforces the SAME invariants the real
 * server must keep by reusing the `:core` [ProjectGuard] (one rule set, no drift): id charset/uniqueness on
 * create, existence on rename, and the delete-safety guardrails (`last_project` / `active_project_protected`
 * checked in that order). [denyWrites] models the operator gate (server 403) for the fail-closed test.
 */
class StubProjectRepository(
    initial: List<Project> = DEFAULT_PROJECTS,
    activeProjectId: String = "default",
    private val denyWrites: String? = null,
) : ProjectRepository {

    private val projects: MutableList<Project> = initial.toMutableList()
    private var active: String = activeProjectId

    override suspend fun list(): ProjectsView = ProjectsView(active, projects.toList())

    override suspend fun create(request: CreateProjectRequest): Project {
        denyWrites?.let { throw ProjectException(it) }
        ProjectGuard.validateCreate(projects, request)?.let { throw ProjectException(it) }
        val created = Project(request.id, request.name.trim())
        projects.add(created)
        // A new project is NOT auto-activated (no unannounced context switch — §3.2).
        return created
    }

    override suspend fun switchActive(projectId: String): ProjectsView {
        denyWrites?.let { throw ProjectException(it) }
        if (projects.none { it.id == projectId }) throw ProjectException("project_not_found")
        active = projectId
        return ProjectsView(active, projects.toList())
    }

    override suspend fun rename(id: String, request: RenameProjectRequest): Project {
        denyWrites?.let { throw ProjectException(it) }
        ProjectGuard.validateRename(projects, id, request.name)?.let { throw ProjectException(it) }
        val index = projects.indexOfFirst { it.id == id }
        val updated = projects[index].copy(name = request.name.trim())
        projects[index] = updated
        return updated
    }

    override suspend fun delete(id: String, deleteWorktrees: Boolean) {
        denyWrites?.let { throw ProjectException(it) }
        ProjectGuard.validateDelete(projects, id, active)?.let { throw ProjectException(it) }
        projects.removeAll { it.id == id }
        // The active project is guard-protected above, so `active` never points at a deleted project.
        // deleteWorktrees is the connector's destructive flag in prod; the stub just drops the project.
    }

    companion object {
        /** Single-project start (matches a fresh platform): only `default`, which is also active. */
        val DEFAULT_PROJECTS: List<Project> = listOf(Project("default", "Default"))
    }
}
