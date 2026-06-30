package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentMgmtGuard
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.CreatedAgent
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.WorktreeFate
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.NotFoundException

/**
 * Runtime agent CRUD (S14 / CYP-97 — AGENT-MANAGEMENT §2). Orchestrates the touchy moving parts behind
 * the operator-gated endpoints: the [AgentMgmtGuard] invariants, the [HubState] topology (spoke + ACL),
 * the [AgentConfigRegistry] (persona/launch the connector spawns with), the [LifecycleManager] (known
 * set + stop), and the worktree side-effects — each delegated, never re-implemented.
 *
 * Disclosure model the doc requires: **add does NOT spawn** (the agent is STOPPED; start is the CYP-73
 * lifecycle); **edit takes effect on the next spawn** (the connector reads [AgentConfigRegistry] at
 * `open()`); **remove stops the session** and, only on the warned path, deletes the worktree (the agent
 * branch is never auto-deleted). All guards are fail-closed: a rejection mutates nothing.
 */
class AgentManagement(
    private val state: HubState,
    private val lifecycle: LifecycleManager,
    private val configs: AgentConfigRegistry,
    private val ensureWorktree: (worktreeName: String) -> Unit,
    private val deleteWorktree: (worktreeName: String) -> Unit,
    /**
     * CYP-122: invoked when an agent is created with a non-default connector (an opt-in to Connector B).
     * Routes through [ConnectorOptIn] (config + caps re-declare + `connector.optin` audit) so create-as-B
     * is audited identically to the dedicated opt-in change. Default no-op (tests / no-event installs).
     */
    private val onConnectorOptIn: (agentId: String, kind: ConnectorKind) -> Unit = { _, _ -> },
    /**
     * CYP-171 / E2.6 (S3) — mints/revokes the per-agent bearer token for a **remote** create/remove.
     * Null (tests / non-remote installs) → no token issuance. The minted token is disclosed ONCE via the
     * [CreatedAgent] return of [add]; identity stays `token→agentId` (no client-supplied agentId).
     */
    private val remoteToken: RemoteTokenIssuer? = null,
) {
    private val lock = Any()

    /** Lightweight list (GET /api/agents) with each agent's live run-state. */
    fun list(): List<Agent> = state.agents.map { it.copy(runState = lifecycle.runStateOf(it.id) ?: it.runState) }

    /** Edit-prefill detail (CYP-101 / GET /api/agents/{id}) — the real launch + persona. */
    fun detail(id: String): AgentDetail {
        val a = state.agent(id) ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
        val cfg = configs.configOf(id)
        return AgentDetail(a.id, a.name, a.role, a.worktree, cfg?.launch ?: "claude", cfg?.persona)
    }

    /** Register a new agent (NOT spawned). Throws the §2 4xx on a guard violation. Returns the agent and,
     *  for a remote create, the **once-disclosed** minted token (CYP-171). */
    fun add(spec: NewAgentSpec): CreatedAgent = synchronized(lock) {
        AgentMgmtGuard.validateAdd(state.agents, spec)?.let { throw codeToException(it) }
        val worktree = spec.worktree?.ifBlank { null }?.trim() ?: spec.id.trim()
        val agent = Agent(spec.id.trim(), spec.name.trim(), spec.role, worktree, AgentRunState.STOPPED, connectorKind = spec.connectorKind)
        configs.put(agent.id, spec.launch?.ifBlank { null }?.trim() ?: "claude", spec.persona?.ifBlank { null })
        state.addAgent(agent)                 // spoke channel + ACL, projectId-stamped (fail-closed)
        ensureWorktree(worktree)              // create the worktree; CLAUDE.md is written at first spawn
        lifecycle.register(agent.id, worktree) // known + STOPPED — start is the CYP-73 lifecycle
        // CYP-122: a non-default connector at create is an opt-in → audited + caps re-declared (server-enforced).
        if (spec.connectorKind != ConnectorKind.STREAM_JSON) onConnectorOptIn(agent.id, spec.connectorKind)
        // CYP-171: a remote/BYOA agent gets a server-minted per-agent token, disclosed ONCE in this response
        // (SEC-OP1's reserved-id guard in validateAdd already rejected an id colliding with the operator).
        val token = if (spec.remote) remoteToken?.issue(agent.id) else null
        CreatedAgent(agent, token)
    }

    /** Write agent config (effective next spawn). Omitted/blank persona/launch PRESERVE the stored value. */
    fun edit(id: String, edit: AgentEdit): Agent = synchronized(lock) {
        AgentMgmtGuard.validateEdit(state.agents, id, edit)?.let { throw codeToException(it) }
        val cur = configs.configOf(id) ?: AgentRuntimeConfig("claude", null)
        val persona = edit.persona?.ifBlank { null } ?: cur.persona // PRESERVE (no blank→null clear)
        val launch = edit.launch?.ifBlank { null } ?: cur.launch
        configs.put(id, launch, persona)
        // MVP=1-PO: the guard only permits a no-op role change, so the hub Agent's role is unchanged;
        // persona/launch take effect on the next spawn (connector reads configs at open()).
        state.agent(id) ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
    }

    /**
     * Stop + remove the agent; [fate] decides the worktree. Validated BEFORE any destructive step
     * (fail-closed: never stop/delete then reject). Suspends to await the process's death (no zombie).
     */
    suspend fun remove(id: String, fate: WorktreeFate) {
        AgentMgmtGuard.validateRemove(state.agents, id)?.let { throw codeToException(it) }
        val worktree = state.agent(id)?.worktree
            ?: throw NotFoundException("agent '$id' not found", code = "agent_not_found")
        lifecycle.stop(id)        // CYP-73 stop: removeAndAwait — process gone before we drop the agent
        state.removeAgent(id)     // clean topology removal — spoke channel + every ACL entry gone
        configs.remove(id)
        lifecycle.forget(id)
        remoteToken?.revoke(id)   // CYP-171 / SEC5: revoke any minted token (idempotent) — agentFor → null
        if (fate == WorktreeFate.DELETE) deleteWorktree(worktree) // branch agent/<id> NOT touched (§9.3)
    }

    private fun codeToException(code: String): Exception = when (code) {
        "invalid_agent" -> BadRequestException("invalid agent spec", code = "invalid_agent")
        "agent_exists" -> ConflictException("agent already exists", code = "agent_exists")
        "po_already_exists" -> ConflictException("a PO already exists", code = "po_already_exists")
        "last_po" -> ConflictException("cannot remove or demote the only PO", code = "last_po")
        "agent_not_found" -> NotFoundException("agent not found", code = "agent_not_found")
        else -> BadRequestException(code, code = code)
    }
}
