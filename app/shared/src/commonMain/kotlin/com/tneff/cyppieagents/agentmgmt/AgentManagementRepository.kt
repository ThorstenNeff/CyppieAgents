package com.tneff.cyppieagents.agentmgmt

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate

/**
 * Agent-management data port (S14, CYP-86 add / CYP-87 remove / CYP-88 edit) — the contract from
 * `docs/AGENT-MANAGEMENT.md §2`. The wire DTOs ([NewAgentSpec]/[AgentEdit]/[WorktreeFate]) now live in
 * `:core` (reconciled at CYP-97), so the live REST client is a stub→real swap with no UI change; reads
 * reuse the already-shared [Agent]/[Role].
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
 * A management call was rejected. [code] is the server's reason — the wire codes from AGENT-MANAGEMENT
 * §2: `invalid_agent`, `agent_exists`, `po_already_exists`, `last_po`, `agent_not_found`,
 * `operator_required` (403), `unauthorized` (401). The VM maps these to honest, spec-defined disclosure.
 */
class AgentMgmtException(val code: String) : Exception(code)
