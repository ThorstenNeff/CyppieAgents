package com.tneff.cyppieagents.agentmgmt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Add-dialog fields (CYP-86). Role defaults to WORKER; PO is blocked when one already exists. */
data class AddForm(
    val id: String = "",
    val name: String = "",
    val role: Role = Role.WORKER,
    val persona: String = "",
    val launch: String = "",
    val worktree: String = "",
)

/** Edit-dialog fields (CYP-88). id/worktree are identity/path-defining and intentionally NOT here. */
data class EditForm(
    val role: Role = Role.WORKER,
    val persona: String = "",
    val launch: String = "",
)

/**
 * Immutable UI state for the Agent-Management panel (S14). The guardrails (AGENT-MANAGEMENT §1.3) are
 * **derived here so the UI can show them BEFORE the action** — a forbidden choice is disabled with an
 * explanation, not a post-hoc server rejection. Exactly one dialog is open at a time.
 */
data class AgentMgmtUiState(
    val loading: Boolean = true,
    /** Operator token present → mutations enabled; else read-only list + gate hint (fail-closed). */
    val editable: Boolean = false,
    val agents: List<Agent> = emptyList(),
    // CYP-86 add
    val addOpen: Boolean = false,
    val addForm: AddForm = AddForm(),
    val addError: String? = null,
    // CYP-87 remove
    val removeTarget: Agent? = null,
    val removeWorktreeFate: WorktreeFate = WorktreeFate.KEEP,
    val removeError: String? = null,
    // CYP-88 edit
    val editTarget: Agent? = null,
    val editForm: EditForm = EditForm(),
    val editError: String? = null,
    /** Amber "saved ≠ active — restart to apply" hint after a successful edit save. */
    val editEffectHint: Boolean = false,
) {
    private val poCount: Int get() = agents.count { it.role == Role.PO }
    val poExists: Boolean get() = poCount >= 1

    /** Only the single PO is unremovable / unrollable (hub-and-spoke would break, §1.3). */
    fun isOnlyPo(agent: Agent): Boolean = agent.role == Role.PO && poCount == 1

    // --- CYP-86 add guardrails (visible before the action) ---
    /** The PO role option is disabled in Add when a PO already exists. */
    val addPoBlocked: Boolean get() = poExists
    /** The typed id collides with an existing agent → field error + confirm disabled. */
    val addIdCollision: Boolean get() = addForm.id.isNotBlank() && agents.any { it.id == addForm.id }
    val canConfirmAdd: Boolean get() = editable &&
        addForm.id.isNotBlank() && addForm.name.isNotBlank() &&
        !addIdCollision && !(addForm.role == Role.PO && poExists)

    // --- CYP-87 remove guardrail ---
    val canConfirmRemove: Boolean get() {
        val t = removeTarget ?: return false
        return editable && !isOnlyPo(t)
    }

    // --- CYP-88 edit guardrails ---
    /** Choosing PO when another agent already holds it. */
    val editPoTakenByOther: Boolean get() {
        val t = editTarget ?: return false
        return editForm.role == Role.PO && agents.any { it.id != t.id && it.role == Role.PO }
    }
    /** Rolling the only PO away from PO. */
    val editWouldDropLastPo: Boolean get() {
        val t = editTarget ?: return false
        return t.role == Role.PO && editForm.role != Role.PO && poCount == 1
    }
    val canConfirmEdit: Boolean get() =
        editable && editTarget != null && !editPoTakenByOther && !editWouldDropLastPo
}

/**
 * Drives the Agent-Management panel (S14: CYP-86 add / CYP-87 remove / CYP-88 edit) over an
 * [AgentManagementRepository] (stub today; live after the CYP-97 connector seam — no VM/UI change). The
 * UI calls; the connector owns spawn/worktree/CLAUDE.md. After every successful mutation the agent list
 * is **re-fetched** (`GET /api/agents`) — that list also drives the dynamic window set, so an added
 * agent gets a window and a removed one loses it.
 *
 * **Fail-closed:** every open/confirm is a no-op without [AgentMgmtUiState.editable] (defence in depth —
 * the server also enforces the operator gate). Disclosure is honest: add never spawns (start = CYP-73),
 * edit shows the amber "saved ≠ active" hint, remove is gated behind explicit consequences + the
 * unremovable-only-PO rule, all surfaced before the action.
 */
class AgentManagementViewModel(
    private val repository: AgentManagementRepository,
    editable: Boolean = false,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val runScope: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(AgentMgmtUiState(editable = editable))
    val state: StateFlow<AgentMgmtUiState> = _state.asStateFlow()

    init { runScope.launch { reload() } }

    private suspend fun reload() {
        val agents = runCatching { repository.list() }.getOrDefault(emptyList())
        _state.update { it.copy(agents = agents, loading = false) }
    }

    // --- CYP-86 add ---

    fun openAdd() {
        if (!_state.value.editable) return
        _state.update { it.copy(addOpen = true, addForm = AddForm(), addError = null) }
    }

    fun closeAdd() = _state.update { it.copy(addOpen = false, addError = null) }
    fun setAddId(v: String) = _state.update { it.copy(addForm = it.addForm.copy(id = v), addError = null) }
    fun setAddName(v: String) = _state.update { it.copy(addForm = it.addForm.copy(name = v), addError = null) }
    fun setAddRole(r: Role) = _state.update { it.copy(addForm = it.addForm.copy(role = r), addError = null) }
    fun setAddPersona(v: String) = _state.update { it.copy(addForm = it.addForm.copy(persona = v)) }
    fun setAddLaunch(v: String) = _state.update { it.copy(addForm = it.addForm.copy(launch = v)) }
    fun setAddWorktree(v: String) = _state.update { it.copy(addForm = it.addForm.copy(worktree = v)) }

    fun confirmAdd() {
        val s = _state.value
        if (!s.canConfirmAdd) return // fail-closed + guardrail (server also enforces)
        val f = s.addForm
        runScope.launch {
            runCatching {
                repository.add(
                    NewAgentSpec(
                        id = f.id.trim(), name = f.name.trim(), role = f.role,
                        persona = f.persona.ifBlank { null }, launch = f.launch.ifBlank { null },
                        worktree = f.worktree.ifBlank { null },
                    ),
                )
            }.onSuccess {
                reload()
                _state.update { it.copy(addOpen = false, addError = null) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(addError = addErrorKey(e)) }
            }
        }
    }

    // --- CYP-87 remove ---

    fun openRemove(agent: Agent) {
        if (!_state.value.editable) return
        _state.update { it.copy(removeTarget = agent, removeWorktreeFate = WorktreeFate.KEEP, removeError = null) }
    }

    fun closeRemove() = _state.update { it.copy(removeTarget = null, removeError = null) }
    fun setRemoveWorktreeFate(fate: WorktreeFate) = _state.update { it.copy(removeWorktreeFate = fate) }

    fun confirmRemove() {
        val s = _state.value
        val target = s.removeTarget ?: return
        if (!s.canConfirmRemove) return // fail-closed + only-PO guardrail
        runScope.launch {
            runCatching { repository.remove(target.id, s.removeWorktreeFate) }
                .onSuccess {
                    reload()
                    _state.update { it.copy(removeTarget = null, removeError = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(removeError = removeErrorKey(e)) }
                }
        }
    }

    // --- CYP-88 edit ---

    fun openEdit(agent: Agent) {
        if (!_state.value.editable) return
        _state.update {
            it.copy(
                editTarget = agent,
                editForm = EditForm(role = agent.role), // persona/launch are greenfield (not in GET /api/agents)
                editError = null, editEffectHint = false,
            )
        }
    }

    fun closeEdit() = _state.update { it.copy(editTarget = null, editError = null, editEffectHint = false) }
    fun setEditRole(r: Role) = _state.update { it.copy(editForm = it.editForm.copy(role = r), editError = null, editEffectHint = false) }
    fun setEditPersona(v: String) = _state.update { it.copy(editForm = it.editForm.copy(persona = v), editEffectHint = false) }
    fun setEditLaunch(v: String) = _state.update { it.copy(editForm = it.editForm.copy(launch = v), editEffectHint = false) }

    fun confirmEdit() {
        val s = _state.value
        val target = s.editTarget ?: return
        if (!s.canConfirmEdit) return
        val f = s.editForm
        runScope.launch {
            runCatching { repository.edit(target.id, AgentEdit(f.role, f.persona.ifBlank { null }, f.launch.ifBlank { null })) }
                .onSuccess {
                    reload()
                    // Keep the dialog open and show the amber "saved ≠ active" hint (never success-green);
                    // activation is the CYP-73 restart on the agent window (no second mechanism).
                    _state.update { it.copy(editEffectHint = true, editError = null) }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(editError = editErrorKey(e)) }
                }
        }
    }

    // Map the server reason to the spec-defined disclosure (AGENT-MANAGEMENT §2/§4/§5/§6).
    private fun addErrorKey(e: Throwable): String = when ((e as? AgentMgmtException)?.code) {
        "agent_exists" -> "agent_add_id_exists"
        "po_already_exists" -> "agent_add_po_exists"
        "operator_required", "unauthorized" -> "agent_mgmt_operator_required"
        else -> "agent_add_error"
    }

    private fun removeErrorKey(e: Throwable): String = when ((e as? AgentMgmtException)?.code) {
        "last_po" -> "agent_remove_last_po"
        "operator_required", "unauthorized" -> "agent_mgmt_operator_required"
        else -> "agent_remove_error"
    }

    private fun editErrorKey(e: Throwable): String = when ((e as? AgentMgmtException)?.code) {
        "po_already_exists", "last_po" -> "agent_edit_po_exists"
        "operator_required", "unauthorized" -> "agent_mgmt_operator_required"
        else -> "agent_edit_error"
    }
}
