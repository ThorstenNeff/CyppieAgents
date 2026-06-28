package com.tneff.cyppieagents.agentmgmt

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role

/**
 * Agent-management data port (S14, CYP-86 add / CYP-87 remove / CYP-88 edit) — the contract from
 * `docs/AGENT-MANAGEMENT.md §2` that the **connector/backend must still build** (greenfield, tracked as
 * CYP-97). The UI depends only on this abstraction, so the live REST client is a later **stub→real
 * swap** with no UI change. Plain Kotlin types (the wire DTOs are `:core`/backend territory, reconciled
 * at CYP-97); reads reuse the already-shared [Agent]/[Role].
 *
 * **The UI calls; it does not manage.** Spawn, worktree create/delete and CLAUDE.md placement are the
 * connector's (CYP-1) — the UI only sends fields. Adding does **not** spawn (start is the CYP-73
 * lifecycle); editing takes effect on the next spawn; removing stops the session and, on the warned
 * path, deletes the worktree.
 */
interface AgentManagementRepository {
    /** Current agents (`GET /api/agents`) — also drives the dynamic window list. */
    suspend fun list(): List<Agent>

    /** Operator-only: register a new agent (not spawned). Throws [AgentMgmtException] on the §2 codes. */
    suspend fun add(spec: NewAgentSpec): Agent

    /** Operator-only: write agent config (effective next spawn). Throws on `po_already_exists`/`last_po`/… */
    suspend fun edit(id: String, edit: AgentEdit): Agent

    /** Operator-only: stop + remove the agent; [worktree] decides the (warned) worktree fate. */
    suspend fun remove(id: String, worktree: WorktreeFate)
}

/**
 * New-agent fields (CYP-86). The connector owns spawn/worktree/CLAUDE.md; [persona] is the text the
 * connector writes into the worktree's CLAUDE.md (greenfield path B, §2.2). Role is **PO/WORKER only**
 * (Product-Lead is deferred, CYP-98). `null`/blank optional fields fall back to connector defaults.
 */
data class NewAgentSpec(
    val id: String,
    val name: String,
    val role: Role,
    val persona: String? = null,
    val launch: String? = null,
    val worktree: String? = null,
)

/**
 * Editable agent config (CYP-88). **id and worktree are identity/path-defining and intentionally NOT
 * here** (changing them would force a worktree migration — §6). Only role/persona/launch change, and
 * the change is effective on the next spawn (the amber "saved ≠ active" disclosure).
 */
data class AgentEdit(
    val role: Role,
    val persona: String? = null,
    val launch: String? = null,
)

/** Worktree fate on removal (CYP-87): [KEEP] is the safe default; [DELETE] is the warned, destructive path. */
enum class WorktreeFate { KEEP, DELETE }

/**
 * A management call was rejected. [code] is the server's reason — the wire codes from AGENT-MANAGEMENT
 * §2: `invalid_agent`, `agent_exists`, `po_already_exists`, `last_po`, `agent_not_found`,
 * `operator_required` (403), `unauthorized` (401). The VM maps these to honest, spec-defined disclosure.
 */
class AgentMgmtException(val code: String) : Exception(code)
