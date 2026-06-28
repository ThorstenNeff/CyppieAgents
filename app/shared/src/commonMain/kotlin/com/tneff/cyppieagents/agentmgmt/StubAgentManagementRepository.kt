package com.tneff.cyppieagents.agentmgmt
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Role

/**
 * In-memory [AgentManagementRepository] for ungated development and tests (S14) until the connector seam
 * (CYP-97) lands — then the live REST client replaces this with no UI change. Enforces the same
 * invariants the real server must keep, so the guardrail/error paths run serverless:
 *
 * - **Exactly one PO:** adding a second PO, or rolling the only PO away / removing it, throws the
 *   matching code (`po_already_exists` / `last_po`). The UI also blocks these *before* the call.
 * - **Unique id:** a duplicate id throws `agent_exists`.
 * - **Add does not spawn:** a created agent is [AgentRunState.STOPPED] (start is the CYP-73 lifecycle).
 *
 * An optional [denyWrites] models the operator gate (server 403) for the fail-closed test.
 */
class StubAgentManagementRepository(
    initial: List<Agent> = DEFAULT_AGENTS,
    private val denyWrites: String? = null,
) : AgentManagementRepository {

    private val agents: MutableList<Agent> = initial.toMutableList()

    /** Connector-config not on the lightweight [Agent]: id → (launch, persona). Backs [detail] + preserve-on-edit. */
    private val config: MutableMap<String, Pair<String, String?>> =
        initial.associate { it.id to ("claude" to null) }.toMutableMap()

    override suspend fun list(): List<Agent> = agents.toList()

    override suspend fun detail(id: String): AgentDetail {
        val agent = agents.firstOrNull { it.id == id } ?: throw AgentMgmtException("agent_not_found")
        val (launch, persona) = config[id] ?: ("claude" to null)
        return AgentDetail(agent.id, agent.name, agent.role, agent.worktree, launch, persona)
    }

    override suspend fun add(spec: NewAgentSpec): Agent {
        denyWrites?.let { throw AgentMgmtException(it) }
        if (spec.id.isBlank() || spec.name.isBlank()) throw AgentMgmtException("invalid_agent")
        if (agents.any { it.id == spec.id }) throw AgentMgmtException("agent_exists")
        if (spec.role == Role.PO && agents.any { it.role == Role.PO }) throw AgentMgmtException("po_already_exists")
        // Created but NOT spawned — appears stopped; start is the CYP-73 lifecycle (no second mechanism).
        val created = Agent(
            id = spec.id.trim(),
            name = spec.name.trim(),
            role = spec.role,
            worktree = spec.worktree?.ifBlank { null }?.trim() ?: spec.id.trim(),
            runState = AgentRunState.STOPPED,
        )
        agents.add(created)
        config[created.id] = (spec.launch?.ifBlank { null } ?: "claude") to spec.persona?.ifBlank { null }
        return created
    }

    override suspend fun edit(id: String, edit: AgentEdit): Agent {
        denyWrites?.let { throw AgentMgmtException(it) }
        val index = agents.indexOfFirst { it.id == id }
        if (index < 0) throw AgentMgmtException("agent_not_found")
        val current = agents[index]
        // Role → PO guardrails: another PO already holds it, or this is the only PO being rolled away.
        if (edit.role == Role.PO && agents.any { it.id != id && it.role == Role.PO }) {
            throw AgentMgmtException("po_already_exists")
        }
        if (current.role == Role.PO && edit.role != Role.PO && agents.count { it.role == Role.PO } == 1) {
            throw AgentMgmtException("last_po")
        }
        val updated = current.copy(role = edit.role)
        agents[index] = updated
        // CYP-101: omitted/blank persona/launch PRESERVE the stored value (no blank→null clear).
        val (curLaunch, curPersona) = config[id] ?: ("claude" to null)
        config[id] = (edit.launch?.ifBlank { null } ?: curLaunch) to (edit.persona?.ifBlank { null } ?: curPersona)
        return updated
    }

    override suspend fun remove(id: String, worktree: WorktreeFate) {
        denyWrites?.let { throw AgentMgmtException(it) }
        val target = agents.firstOrNull { it.id == id } ?: throw AgentMgmtException("agent_not_found")
        if (target.role == Role.PO && agents.count { it.role == Role.PO } == 1) throw AgentMgmtException("last_po")
        agents.removeAll { it.id == id }
        config.remove(id)
        // worktree=DELETE is the connector's destructive action in prod; the stub just drops the agent.
    }

    companion object {
        /** Mirrors today's static shell set (PO + two workers) so the dynamic window list starts identical. */
        val DEFAULT_AGENTS: List<Agent> = listOf(
            Agent("po", "Product Owner", Role.PO, "po", AgentRunState.RUNNING),
            Agent("frontend", "Frontend", Role.WORKER, "frontend", AgentRunState.RUNNING),
            Agent("backend", "Backend", Role.WORKER, "backend", AgentRunState.RUNNING),
        )
    }
}
