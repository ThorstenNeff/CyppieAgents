package com.tneff.cyppieagents.project

/**
 * `testTag` contract for the project switcher (CYP-92) and project management (CYP-91), exactly per
 * `docs/design/project-management-tags.md` (Epic CYP-78). Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>`, segment values `[A-Za-z0-9-]+` (camelCase, no dots in a value).
 * Areas `projectSwitcher` (top-level bar) + `projectMgmt` (management overlay). **Shared API with QA
 * (CYP-7) — do not rename silently; coordinate via the PO.**
 *
 * Deliberately NO `window.<id>` reuse: neither surface is a canvas window in the `WindowHost` — the
 * switcher is top-level ABOVE the host, the management is an overlay from it (both stand *over* the
 * project scope they manage, so neither may carry a project-scoped window id). (tags.md note.)
 */
object ProjectTags {

    // --- CYP-92 switcher (top-level bar over the WindowHost) ---
    const val BAR = "projectSwitcher.bar"
    const val ACTIVE = "projectSwitcher.active"
    const val MENU = "projectSwitcher.menu"
    const val SCOPE_HINT = "projectSwitcher.scopeHint"
    const val SWITCH_HINT = "projectSwitcher.switchHint"
    const val SINGLE_HINT = "projectSwitcher.singleHint" // CYP-233: single-project onboarding hint
    const val MANAGE = "projectSwitcher.manage"

    fun item(id: String) = "projectSwitcher.item.$id"
    fun itemActive(id: String) = "projectSwitcher.item.$id.active"

    // --- CYP-91 management (overlay) ---
    const val PANEL = "projectMgmt.panel"
    const val GATE_HINT = "projectMgmt.gateHint"
    const val ADD = "projectMgmt.add"
    const val LIST = "projectMgmt.list"
    const val ERROR = "projectMgmt.error"

    fun row(id: String) = "projectMgmt.row.$id"
    fun rowActive(id: String) = "projectMgmt.row.$id.active"
    fun rowRename(id: String) = "projectMgmt.row.$id.rename"
    fun rowDelete(id: String) = "projectMgmt.row.$id.delete"
    fun rowDeleteBlocked(id: String) = "projectMgmt.row.$id.deleteBlocked"

    // Add dialog
    const val ADD_DIALOG = "projectMgmt.addDialog"
    const val ADD_NAME = "projectMgmt.addDialog.name"
    const val ADD_CONFIRM = "projectMgmt.addDialog.confirm"
    const val ADD_CANCEL = "projectMgmt.addDialog.cancel"
    const val ADD_ERROR = "projectMgmt.addDialog.error"

    // Rename dialog
    const val RENAME_DIALOG = "projectMgmt.renameDialog"
    const val RENAME_NAME = "projectMgmt.renameDialog.name"
    const val RENAME_CONFIRM = "projectMgmt.renameDialog.confirm"
    const val RENAME_CANCEL = "projectMgmt.renameDialog.cancel"
    const val RENAME_ERROR = "projectMgmt.renameDialog.error"

    // Delete dialog (irreversible + cascading — reuse the RemoveDialog pattern)
    const val DELETE_DIALOG = "projectMgmt.deleteDialog"
    const val DELETE_CONSEQUENCES = "projectMgmt.deleteDialog.consequences"
    const val DELETE_COUNTS = "projectMgmt.deleteDialog.counts"
    const val DELETE_WORKTREE_CHOICE = "projectMgmt.deleteDialog.worktreeChoice"
    const val DELETE_WORKTREE_KEEP = "projectMgmt.deleteDialog.worktreeKeep"
    const val DELETE_WORKTREE_DELETE = "projectMgmt.deleteDialog.worktreeDelete"
    const val DELETE_WORKTREE_WARNING = "projectMgmt.deleteDialog.worktreeWarning"
    const val DELETE_CONFIRM = "projectMgmt.deleteDialog.confirm"
    const val DELETE_CANCEL = "projectMgmt.deleteDialog.cancel"
    const val DELETE_ERROR = "projectMgmt.deleteDialog.error"
}
