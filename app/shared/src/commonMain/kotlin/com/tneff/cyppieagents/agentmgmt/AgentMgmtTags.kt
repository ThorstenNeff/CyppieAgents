package com.tneff.cyppieagents.agentmgmt

/**
 * `testTag` contract for the Agent-Management panel (CYP-86 add / CYP-87 remove / CYP-88 edit), exactly
 * per `docs/design/agent-management-tags.md` (Epic CYP-76). Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>`, segment values `[A-Za-z0-9-]+` (camelCase, no dots). Area `agentMgmt`,
 * single-instance. **Shared API with QA (CYP-7) — do not rename silently; coordinate via the PO.**
 *
 * The window mounts under `window.agentMgmt.content`; the per-row lifecycle status/actions REUSE the
 * existing CYP-73 `agent.<id>.{status,startBtn,stopBtn,restartBtn}` ([com.tneff.cyppieagents.agentview.AgentViewTags])
 * — this panel renders no lifecycle control of its own (AGENT-MANAGEMENT §7).
 */
object AgentMgmtTags {
    const val AREA = "agentMgmt"

    const val PANEL = "agentMgmt.panel"
    const val LIST = "agentMgmt.list"
    const val ADD_BUTTON = "agentMgmt.addButton"
    const val GATE_HINT = "agentMgmt.gateHint"
    const val EMPTY = "agentMgmt.empty" // CYP-228: empty-state onboarding when agents.isEmpty()

    /** CYP-288 — load-error surface (shown INSTEAD of EMPTY when the agent-list load failed) + its retry button. */
    const val ERROR = "agentMgmt.error"
    const val ERROR_RETRY = "agentMgmt.error.retry"

    fun item(id: String) = "agentMgmt.item.$id"
    fun itemEdit(id: String) = "agentMgmt.item.$id.edit"
    fun itemRemove(id: String) = "agentMgmt.item.$id.remove"

    // CYP-86 — add
    const val ADD_DIALOG = "agentMgmt.add.dialog"
    const val ADD_ID_INPUT = "agentMgmt.add.id.input"
    const val ADD_NAME_INPUT = "agentMgmt.add.name.input"
    const val ADD_ROLE_PICKER = "agentMgmt.add.role.picker"
    const val ADD_PERSONA_INPUT = "agentMgmt.add.persona.input"
    const val ADD_LAUNCH_INPUT = "agentMgmt.add.launch.input"
    const val ADD_WORKTREE_INPUT = "agentMgmt.add.worktree.input"
    const val ADD_CONFIRM = "agentMgmt.add.confirm"
    const val ADD_CANCEL = "agentMgmt.add.cancel"
    const val ADD_SPAWN_HINT = "agentMgmt.add.spawnHint"
    const val ADD_PROJECT_NOTE = "agentMgmt.add.projectNote" // CYP-228: "added to the active project <name>" (dialog head)
    const val ADD_AUTO_NOTE = "agentMgmt.add.autoNote" // CYP-228: "token/branch/channel are auto-assigned"
    const val ADD_REMOTE_TOGGLE = "agentMgmt.add.remote.toggle" // CYP-899: remote/BYOA create toggle
    const val ADD_REMOTE_HINT = "agentMgmt.add.remote.hint" // CYP-899: remote-agent explanation
    const val ADD_ERROR = "agentMgmt.add.error"
    const val ADD_SUCCESS = "agentMgmt.add.success" // CYP-314: panel-level INFO confirmation after a successful create
    // CYP-900 (B2): one-time minted-token reveal (remote/BYOA create) — shown-once, copyable, never logged.
    const val ADD_TOKEN_REVEAL = "agentMgmt.add.token.reveal"
    const val ADD_TOKEN_VALUE = "agentMgmt.add.token.value"
    const val ADD_TOKEN_COPY = "agentMgmt.add.token.copy"
    const val ADD_TOKEN_DISMISS = "agentMgmt.add.token.dismiss"

    /** CYP-907 (B3) — the remote/BYOA-origin chip on a roster row (present iff `agent.remote`). */
    fun itemRemote(agentId: String) = "agentMgmt.item.$agentId.remote"

    // CYP-87 — remove (irreversible)
    const val REMOVE_DIALOG = "agentMgmt.remove.dialog"
    const val REMOVE_CONSEQUENCES = "agentMgmt.remove.consequences"
    const val REMOVE_WORKTREE_CHOICE = "agentMgmt.remove.worktreeChoice"
    const val REMOVE_WORKTREE_WARNING = "agentMgmt.remove.worktreeWarning"
    const val REMOVE_CONFIRM = "agentMgmt.remove.confirm"
    const val REMOVE_CANCEL = "agentMgmt.remove.cancel"
    const val REMOVE_ERROR = "agentMgmt.remove.error"

    // CYP-88 — edit config
    const val EDIT_DIALOG = "agentMgmt.edit.dialog"
    const val EDIT_ROLE_PICKER = "agentMgmt.edit.role.picker"
    const val EDIT_PERSONA_INPUT = "agentMgmt.edit.persona.input"
    const val EDIT_LAUNCH_INPUT = "agentMgmt.edit.launch.input"
    const val EDIT_EFFECT_HINT = "agentMgmt.edit.effectHint"
    const val EDIT_SAVE = "agentMgmt.edit.save"
    const val EDIT_CANCEL = "agentMgmt.edit.cancel"
    const val EDIT_ERROR = "agentMgmt.edit.error"

    /** Role-picker option (PO/WORKER), scoped by role wire-name — for selecting/asserting an option. */
    fun roleOption(picker: String, role: String) = "$picker.$role"
}
