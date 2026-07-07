package com.tneff.cyppieagents.model

/**
 * The agent-management invariants (S14 / CYP-97 — AGENT-MANAGEMENT §2/§9), as a **pure** decision over
 * the current agent list — the management analogue of [AclGuard]. Compiled in `:core` so the server
 * enforcement and the client stub/UI use the EXACT same rules (no drift), and so the touchy guardrails
 * (exactly one PO; the only PO is undeletable/unrollable; unique id) are testable platform-neutrally.
 *
 * Each function returns the wire error code to reject with, or `null` when the change is allowed —
 * fail-closed at the call site (the server throws the matching 4xx; the UI blocks before calling).
 */
object AgentMgmtGuard {

    /** `null` if [spec] may be added, else `invalid_agent` / `agent_exists` / `po_already_exists`. */
    fun validateAdd(existing: List<Agent>, spec: NewAgentSpec): String? {
        if (spec.id.isBlank() || spec.name.isBlank() || !SAFE_ID.matches(spec.id)) return "invalid_agent"
        // CYP-171 / SEC-OP1: an agent id may NEVER collide with a reserved non-agent participant id (the
        // operator). The duplicate-check below only scans the AGENT list, but the operator is a separate
        // participant — without this, `POST /api/agents {id:"operator"}` would slip through, S3 would mint
        // a token, and `agentFor(minted) == OPERATOR_ID` → the remote holder is treated as the operator
        // participant in comm/ACL (identity confusion). Reject fail-closed, BEFORE any mint.
        if (spec.id in RESERVED_AGENT_IDS) return "invalid_agent"
        if (existing.any { it.id == spec.id }) return "agent_exists"
        if (spec.role == Role.PO && existing.any { it.role == Role.PO }) return "po_already_exists"
        return null
    }

    /**
     * CYP-171 / SEC-OP1 — ids reserved for **non-agent participants**; an agent may never claim one. Must
     * include `HubState.OPERATOR_ID` ("operator"); a `:server` drift-test pins that membership so the two
     * can't diverge (single-source). Any future privileged non-agent participant id is added here.
     */
    val RESERVED_AGENT_IDS: Set<String> = setOf("operator")

    /** `null` if [edit] may be applied to [id], else `agent_not_found` / `po_already_exists` / `last_po`. */
    fun validateEdit(existing: List<Agent>, id: String, edit: AgentEdit): String? {
        val current = existing.firstOrNull { it.id == id } ?: return "agent_not_found"
        // CYP-313: role is nullable (null = PRESERVE, like name/color/launch). ONLY an explicit role change
        // (edit.role != null) can touch the PO topology; a display-only edit (colour/name) omits role and
        // must never be read as a demotion of the only PO. `po_already_exists`/`last_po` fire on non-null role.
        val newRole = edit.role ?: return null
        // A second PO is never allowed; rolling the only PO away breaks hub-and-spoke.
        if (newRole == Role.PO && existing.any { it.id != id && it.role == Role.PO }) return "po_already_exists"
        if (current.role == Role.PO && newRole != Role.PO && existing.count { it.role == Role.PO } == 1) return "last_po"
        return null
    }

    /** `null` if [id] may be removed, else `agent_not_found` / `last_po` (the only PO is undeletable). */
    fun validateRemove(existing: List<Agent>, id: String): String? {
        val target = existing.firstOrNull { it.id == id } ?: return "agent_not_found"
        if (target.role == Role.PO && existing.count { it.role == Role.PO } == 1) return "last_po"
        return null
    }

    /**
     * Agent ids become a worktree folder, a hub channel id (`po-<id>`) and a git branch (`agent/<id>`),
     * so they are constrained to a path/ref-safe charset — a stray `/`, space or `..` would escape into
     * the filesystem / a malformed ref. Anything else is `invalid_agent`, fail-closed.
     */
    private val SAFE_ID = Regex("^[a-zA-Z0-9_-]+$")
}
