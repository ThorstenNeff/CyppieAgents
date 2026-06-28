package com.tneff.cyppieagents.project

import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectsView
import com.tneff.cyppieagents.model.RenameProjectRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-91/92 ViewModel contract. The delete-safety guardrails reuse `:core` ProjectGuard, so the same
 * rules the server enforces are asserted here; switching is non-destructive; every mutation is fail-closed
 * without an operator token. The non-suspending stub settles synchronously under [Dispatchers.Unconfined].
 */
class ProjectViewModelTest {

    private fun vm(repo: ProjectRepository, editable: Boolean = true) =
        ProjectViewModel(repo, editable = editable, scope = CoroutineScope(Dispatchers.Unconfined))

    private fun stub(vararg projects: Project, active: String = "default", deny: String? = null) =
        StubProjectRepository(
            if (projects.isEmpty()) listOf(Project("default", "Default")) else projects.toList(),
            active, deny,
        )

    private val default = Project("default", "Default")
    private val other = Project("other", "Other")

    @Test
    fun load_populatesActiveAndProjects() {
        val vm = vm(stub(default, other, active = "default"))
        assertEquals("default", vm.state.value.activeProjectId)
        assertEquals(listOf("default", "other"), vm.state.value.projects.map { it.id })
        assertFalse(vm.state.value.loading)
    }

    // --- CYP-92 switch ---

    @Test
    fun switch_flipsActive_andRefetches() {
        val vm = vm(stub(default, other, active = "default"))
        vm.switchTo("other")
        assertEquals("other", vm.state.value.activeProjectId)
        assertFalse(vm.state.value.menuOpen)
    }

    @Test
    fun switch_toActive_isNoOp() {
        val vm = vm(stub(default, other, active = "default"))
        vm.openMenu()
        vm.switchTo("default") // already active
        assertEquals("default", vm.state.value.activeProjectId)
        assertFalse(vm.state.value.menuOpen) // still closes the menu
    }

    @Test
    fun switch_failClosed_withoutOperator() {
        val vm = vm(stub(default, other, active = "default"), editable = false)
        vm.switchTo("other")
        assertEquals("default", vm.state.value.activeProjectId) // no switch without operator
    }

    // --- CYP-91 add ---

    @Test
    fun add_create_appendsProject_idSluggedFromName() {
        val vm = vm(stub(default))
        vm.openAdd(); vm.setAddName("New Proj")
        assertTrue(vm.state.value.canConfirmAdd)
        vm.confirmAdd()
        val created = vm.state.value.projects.firstOrNull { it.id == "new-proj" }
        assertEquals("New Proj", created?.name)
        assertFalse(vm.state.value.addOpen)
    }

    @Test
    fun add_duplicateName_blocksConfirm_viaProjectGuard() {
        val vm = vm(stub(default))
        vm.openAdd(); vm.setAddName("Default") // slugs to existing id "default"
        assertEquals("project_exists", vm.state.value.addCreateCode)
        assertFalse(vm.state.value.canConfirmAdd)
    }

    @Test
    fun add_failClosed_withoutOperator() {
        val vm = vm(stub(default), editable = false)
        vm.openAdd()
        assertFalse(vm.state.value.addOpen) // no-op
        assertFalse(vm.state.value.canConfirmAdd)
    }

    @Test
    fun add_serverGateError_mapsToOperatorRequired() {
        // Local guard passes (valid new name) but the server denies — proves the gate-error mapping.
        val vm = vm(stub(default, deny = "operator_required"))
        vm.openAdd(); vm.setAddName("Fresh")
        vm.confirmAdd()
        assertEquals("project_mgmt_operator_required", vm.state.value.addError)
    }

    // --- CYP-91 rename ---

    @Test
    fun rename_updatesName_idStable() {
        val vm = vm(stub(default, other))
        vm.openRename(other); vm.setRenameName("Renamed")
        assertTrue(vm.state.value.canConfirmRename)
        vm.confirmRename()
        val renamed = vm.state.value.projects.first { it.id == "other" }
        assertEquals("Renamed", renamed.name)
    }

    @Test
    fun rename_toAnotherProjectsName_blocks() {
        val vm = vm(stub(default, other))
        vm.openRename(other); vm.setRenameName("Default") // taken by another project
        assertTrue(vm.state.value.renameNameTaken)
        assertFalse(vm.state.value.canConfirmRename)
    }

    // --- CYP-91 delete-safety (ProjectGuard) ---

    @Test
    fun delete_activeProject_blocked_withVisibleReason() {
        val vm = vm(stub(default, other, active = "default"))
        assertEquals("active_project_protected", vm.state.value.deleteBlockedCode(default))
        assertFalse(vm.state.value.canDelete(default))
        vm.openDelete(default)
        assertNull(vm.state.value.deleteTarget) // blocked → no dialog opens
    }

    @Test
    fun delete_lastProject_blocked() {
        val vm = vm(stub(default, active = "default")) // single project
        assertEquals("last_project", vm.state.value.deleteBlockedCode(default))
        assertFalse(vm.state.value.canDelete(default))
    }

    @Test
    fun delete_nonActiveProject_succeeds() {
        val vm = vm(stub(default, other, active = "default"))
        assertTrue(vm.state.value.canDelete(other))
        vm.openDelete(other)
        assertEquals("other", vm.state.value.deleteTarget?.id)
        vm.confirmDelete()
        assertEquals(listOf("default"), vm.state.value.projects.map { it.id })
        assertNull(vm.state.value.deleteTarget)
    }

    // The local guards usually block active/last delete, but the SERVER stays authoritative — a race that
    // reaches the server must map each 409 to the right inline key (mapping table). Direct, mutation-provable:
    // drop a deleteErrorKey/renameErrorKey branch → exactly that test goes RED (the delete-safety UX, locked).

    @Test
    fun delete_serverReason_activeProtected_mapsToActiveBlockedKey() {
        val vm = vm(ThrowingRepo("active_project_protected"))
        vm.openDelete(other) // non-active → guard allows opening
        vm.confirmDelete()
        assertEquals("project_delete_active_blocked", vm.state.value.deleteError)
    }

    @Test
    fun delete_serverReason_lastProject_mapsToLastBlockedKey() {
        val vm = vm(ThrowingRepo("last_project"))
        vm.openDelete(other)
        vm.confirmDelete()
        assertEquals("project_delete_last_blocked", vm.state.value.deleteError)
    }

    @Test
    fun rename_serverReason_invalidId_mapsToNameEmpty() {
        val vm = vm(ThrowingRepo("invalid_project_id"))
        vm.openRename(other); vm.setRenameName("X")
        vm.confirmRename()
        assertEquals("project_add_name_empty", vm.state.value.renameError)
    }

    @Test
    fun rename_serverReason_notFound_mapsToRenameError() {
        val vm = vm(ThrowingRepo("project_not_found"))
        vm.openRename(other); vm.setRenameName("X")
        vm.confirmRename()
        assertEquals("project_rename_error", vm.state.value.renameError)
    }

    @Test
    fun delete_worktreeFate_defaultsKeep_andTogglesToDelete() {
        val vm = vm(stub(default, other, active = "default"))
        vm.openDelete(other)
        assertFalse(vm.state.value.deleteWorktrees) // keep is the default
        vm.setDeleteWorktrees(true)
        assertTrue(vm.state.value.deleteWorktrees)
    }
}

/** A repository whose every mutation throws a fixed server [code] — for the error-mapping pins. */
private class ThrowingRepo(private val code: String) : ProjectRepository {
    private val view = ProjectsView("default", listOf(Project("default", "Default"), Project("other", "Other")))
    override suspend fun list(): ProjectsView = view
    override suspend fun create(request: CreateProjectRequest): Project = throw ProjectException(code)
    override suspend fun switchActive(projectId: String): ProjectsView = throw ProjectException(code)
    override suspend fun rename(id: String, request: RenameProjectRequest): Project = throw ProjectException(code)
    override suspend fun delete(id: String, deleteWorktrees: Boolean): Unit = throw ProjectException(code)
}
