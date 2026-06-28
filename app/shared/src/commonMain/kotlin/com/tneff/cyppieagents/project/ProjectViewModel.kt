package com.tneff.cyppieagents.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.Project
import com.tneff.cyppieagents.model.ProjectGuard
import com.tneff.cyppieagents.model.RenameProjectRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Immutable UI state shared by the project switcher (CYP-92) and the project management overlay (CYP-91).
 * The delete-safety guardrails (PROJECT-MANAGEMENT §4.3) are derived here via the `:core` [ProjectGuard] so
 * the UI shows them **before** the action (disabled control + inline reason) using the EXACT rules the
 * server enforces — UI guards are advisory, the server stays authoritative (CYP-49 precedent).
 *
 * `activeProjectId` is server state (`ProjectsView`): the UI never invents it. Switching is a non-destructive
 * context change; only delete is destructive (full confirm/consequence/worktree-fate regime).
 */
data class ProjectUiState(
    val loading: Boolean = true,
    /** Operator token present → mutations + switching enabled; else read-only + gate hint (fail-closed). */
    val editable: Boolean = false,
    val activeProjectId: String = "default",
    val projects: List<Project> = emptyList(),
    // switcher
    val menuOpen: Boolean = false,
    val manageOpen: Boolean = false,
    // add
    val addOpen: Boolean = false,
    val addName: String = "",
    val addError: String? = null,
    // rename
    val renameTarget: Project? = null,
    val renameName: String = "",
    val renameError: String? = null,
    // delete
    val deleteTarget: Project? = null,
    val deleteWorktrees: Boolean = false,
    val deleteError: String? = null,
) {
    val isSingleProject: Boolean get() = projects.size <= 1
    fun isActive(project: Project): Boolean = project.id == activeProjectId

    // --- CYP-91 add guardrails (visible before the action; ProjectGuard = server's rules) ---
    /** The server reason a create with this name would hit, or null. id is slugged from the name. */
    val addCreateCode: String?
        get() = if (addName.isBlank()) null
        else ProjectGuard.validateCreate(projects, CreateProjectRequest(slugId(addName), addName.trim()))
    val canConfirmAdd: Boolean get() = editable && addName.isNotBlank() && addCreateCode == null

    // --- CYP-91 rename guardrails ---
    /** Renaming to a label another project already uses (advisory — ids stay unique; UX clarity). */
    val renameNameTaken: Boolean
        get() {
            val t = renameTarget ?: return false
            return renameName.isNotBlank() && projects.any { it.id != t.id && it.name == renameName.trim() }
        }
    val canConfirmRename: Boolean
        get() = editable && renameTarget != null && renameName.isNotBlank() && !renameNameTaken

    // --- CYP-91 delete-safety guardrails (PROJECT-MANAGEMENT §4.3, ProjectGuard) ---
    /** The blocking reason code for deleting [project] (`last_project`/`active_project_protected`), or null. */
    fun deleteBlockedCode(project: Project): String? =
        ProjectGuard.validateDelete(projects, project.id, activeProjectId)
    fun canDelete(project: Project): Boolean = editable && deleteBlockedCode(project) == null
    val canConfirmDelete: Boolean
        get() {
            val t = deleteTarget ?: return false
            return editable && deleteBlockedCode(t) == null
        }
}

/** Path/ref-safe id slugged from a display name (server re-validates via [ProjectGuard.SAFE_ID]). */
internal fun slugId(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')

/**
 * Drives the project switcher (CYP-92) + management overlay (CYP-91) over a [ProjectRepository] (stub today;
 * live swap later, no UI/VM change). After every successful mutation/switch the view is **re-fetched** from
 * the server view (`ProjectsView`) so the active pointer and the list stay authoritative.
 *
 * **Fail-closed:** every mutating open/confirm and the switch are no-ops without [ProjectUiState.editable]
 * (defence in depth — the server also enforces the operator gate). Switching is non-destructive; delete is
 * the only destructive op and carries the full consequence/worktree-fate/named-button regime.
 */
class ProjectViewModel(
    private val repository: ProjectRepository,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ProjectUiState(editable = editable))
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()

    init { runScope.launch { reload() } }

    private suspend fun reload() {
        runCatching { repository.list() }
            .onSuccess { view ->
                _state.update { it.copy(activeProjectId = view.activeProjectId, projects = view.projects, loading = false) }
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false) } // keep prior list/active; fail-closed (no invented data)
            }
    }

    // --- CYP-92 switcher ---

    fun openMenu() = _state.update { it.copy(menuOpen = true) }
    fun closeMenu() = _state.update { it.copy(menuOpen = false) }

    /** Flip the active pointer (operator-gated). No-op for the already-active project. Re-fetches the view. */
    fun switchTo(projectId: String) {
        val s = _state.value
        if (!s.editable || projectId == s.activeProjectId) {
            _state.update { it.copy(menuOpen = false) }
            return
        }
        runScope.launch {
            runCatching { repository.switchActive(projectId) }
                .onSuccess { view ->
                    _state.update {
                        it.copy(activeProjectId = view.activeProjectId, projects = view.projects, menuOpen = false)
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(menuOpen = false) } // server rejected; stay on current project
                }
        }
    }

    fun openManage() = _state.update { it.copy(manageOpen = true, menuOpen = false) }
    fun closeManage() = _state.update { it.copy(manageOpen = false) }

    // --- CYP-91 add ---

    fun openAdd() {
        if (!_state.value.editable) return
        _state.update { it.copy(addOpen = true, addName = "", addError = null) }
    }
    fun closeAdd() = _state.update { it.copy(addOpen = false, addError = null) }
    fun setAddName(v: String) = _state.update { it.copy(addName = v, addError = null) }

    fun confirmAdd() {
        val s = _state.value
        if (!s.canConfirmAdd) return // fail-closed + guardrail (server also enforces)
        val name = s.addName.trim()
        runScope.launch {
            runCatching { repository.create(CreateProjectRequest(slugId(name), name)) }
                .onSuccess {
                    reload()
                    _state.update { it.copy(addOpen = false, addError = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(addError = createErrorKey(e)) }
                }
        }
    }

    // --- CYP-91 rename ---

    fun openRename(project: Project) {
        if (!_state.value.editable) return
        _state.update { it.copy(renameTarget = project, renameName = project.name, renameError = null) }
    }
    fun closeRename() = _state.update { it.copy(renameTarget = null, renameError = null) }
    fun setRenameName(v: String) = _state.update { it.copy(renameName = v, renameError = null) }

    fun confirmRename() {
        val s = _state.value
        val target = s.renameTarget ?: return
        if (!s.canConfirmRename) return
        runScope.launch {
            runCatching { repository.rename(target.id, RenameProjectRequest(s.renameName.trim())) }
                .onSuccess {
                    reload()
                    _state.update { it.copy(renameTarget = null, renameError = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(renameError = renameErrorKey(e)) }
                }
        }
    }

    // --- CYP-91 delete (irreversible + cascading) ---

    fun openDelete(project: Project) {
        val s = _state.value
        if (!s.editable || s.deleteBlockedCode(project) != null) return // blocked → row button disabled, no dialog
        _state.update { it.copy(deleteTarget = project, deleteWorktrees = false, deleteError = null) }
    }
    fun closeDelete() = _state.update { it.copy(deleteTarget = null, deleteError = null) }
    fun setDeleteWorktrees(delete: Boolean) = _state.update { it.copy(deleteWorktrees = delete) }

    fun confirmDelete() {
        val s = _state.value
        val target = s.deleteTarget ?: return
        if (!s.canConfirmDelete) return
        runScope.launch {
            runCatching { repository.delete(target.id, s.deleteWorktrees) }
                .onSuccess {
                    reload()
                    _state.update { it.copy(deleteTarget = null, deleteError = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(deleteError = deleteErrorKey(e)) }
                }
        }
    }

    // Map the server reason to the spec-defined disclosure (PROJECT-MANAGEMENT §6.4 / keys.md mapping table).
    private fun createErrorKey(e: Throwable): String = when ((e as? ProjectException)?.code) {
        "invalid_project_id" -> "project_add_name_empty"
        "project_exists" -> "project_add_name_exists"
        "operator_required", "unauthorized" -> "project_mgmt_operator_required"
        else -> "project_add_error"
    }

    private fun renameErrorKey(e: Throwable): String = when ((e as? ProjectException)?.code) {
        "invalid_project_id" -> "project_add_name_empty"
        "operator_required", "unauthorized" -> "project_mgmt_operator_required"
        else -> "project_rename_error"
    }

    private fun deleteErrorKey(e: Throwable): String = when ((e as? ProjectException)?.code) {
        "last_project" -> "project_delete_last_blocked"
        "active_project_protected" -> "project_delete_active_blocked"
        "operator_required", "unauthorized" -> "project_mgmt_operator_required"
        else -> "project_delete_error"
    }
}
