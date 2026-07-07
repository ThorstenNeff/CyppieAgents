package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentMgmtGuard
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure agent-management invariants (S14 / CYP-97). Platform-neutral so the server enforcement and
 * the client stub/UI share the exact rules — fail-closed, exactly-one-PO, the only PO is undeletable.
 */
class AgentMgmtGuardTest {

    private val agents = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
    )

    private fun add(id: String, role: Role = Role.WORKER, name: String = "X") = NewAgentSpec(id, name, role)

    // ---- add ----

    @Test fun add_ok() = assertNull(AgentMgmtGuard.validateAdd(agents, add("backend")))

    @Test fun add_blankOrBadIdOrName_invalidAgent() {
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, add("")))
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, NewAgentSpec("x", "", Role.WORKER)))
        // path/ref-unsafe ids are rejected (id → worktree folder / channel id / git branch)
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, add("../escape")))
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, add("a b")))
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, add("a/b")))
    }

    @Test fun add_duplicateId_agentExists() =
        assertEquals("agent_exists", AgentMgmtGuard.validateAdd(agents, add("frontend")))

    /**
     * CYP-171 / SEC-OP1: an agent id may NEVER collide with a reserved non-agent participant id (the
     * operator). The duplicate-check only scans the agent list — the operator is a separate participant —
     * so without this a `POST /api/agents {id:"operator"}` would slip through and S3 would mint a token
     * resolving to the operator participant. Reject `invalid_agent`, BEFORE any mint. (Mutation: drop the
     * reserved-id check → this reds.)
     */
    @Test fun add_reservedOperatorId_invalidAgent() {
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, add("operator")))
        assertEquals("invalid_agent", AgentMgmtGuard.validateAdd(agents, NewAgentSpec("operator", "X", Role.WORKER, remote = true)))
        assertTrue(AgentMgmtGuard.RESERVED_AGENT_IDS.contains("operator"))
    }

    @Test fun add_secondPo_poAlreadyExists() =
        assertEquals("po_already_exists", AgentMgmtGuard.validateAdd(agents, add("po2", Role.PO)))

    // ---- edit ----

    @Test fun edit_workerStaysWorker_ok() =
        assertNull(AgentMgmtGuard.validateEdit(agents, "frontend", AgentEdit(Role.WORKER, persona = "new")))

    @Test fun edit_unknown_agentNotFound() =
        assertEquals("agent_not_found", AgentMgmtGuard.validateEdit(agents, "ghost", AgentEdit(Role.WORKER)))

    @Test fun edit_promoteSecondPo_poAlreadyExists() =
        assertEquals("po_already_exists", AgentMgmtGuard.validateEdit(agents, "frontend", AgentEdit(Role.PO)))

    @Test fun edit_rollOnlyPoAway_lastPo() =
        assertEquals("last_po", AgentMgmtGuard.validateEdit(agents, "po", AgentEdit(Role.WORKER)))

    // ---- CYP-313: role is nullable (null = PRESERVE); only an EXPLICIT role change touches the topology ----

    /**
     * (c) THE BUG: a display-only edit of the only PO (colour/name changed, `role` OMITTED → null) must be
     * ALLOWED. Before CYP-313 `role` was a required field, so a colour edit had to carry a role and a
     * non-PO one was mis-read as demoting the only PO → false `last_po`. (Mutation: drop the `?: return
     * null` short-circuit in validateEdit → this reds with `last_po`.)
     */
    @Test fun edit_nullRoleDisplayEditOfOnlyPo_ok() {
        assertNull(AgentMgmtGuard.validateEdit(agents, "po", AgentEdit(role = null, color = "#123456")))
        assertNull(AgentMgmtGuard.validateEdit(agents, "po", AgentEdit(role = null, name = "Product Owner")))
        // and a null-role edit of a worker is likewise a no-op for the topology
        assertNull(AgentMgmtGuard.validateEdit(agents, "frontend", AgentEdit(role = null, color = "#abcdef")))
    }

    /** (a) an EXPLICIT (non-null) demote of the only PO still reds `last_po` — the guard stays sharp. */
    @Test fun edit_explicitDemoteOnlyPo_lastPoStaysSharp() =
        assertEquals("last_po", AgentMgmtGuard.validateEdit(agents, "po", AgentEdit(role = Role.WORKER)))

    /** (b) an EXPLICIT (non-null) second PO still reds `po_already_exists` — the guard stays sharp. */
    @Test fun edit_explicitSecondPo_poAlreadyExistsStaysSharp() =
        assertEquals("po_already_exists", AgentMgmtGuard.validateEdit(agents, "frontend", AgentEdit(role = Role.PO)))

    // ---- remove ----

    @Test fun remove_worker_ok() = assertNull(AgentMgmtGuard.validateRemove(agents, "frontend"))

    @Test fun remove_unknown_agentNotFound() =
        assertEquals("agent_not_found", AgentMgmtGuard.validateRemove(agents, "ghost"))

    @Test fun remove_onlyPo_lastPo() =
        assertEquals("last_po", AgentMgmtGuard.validateRemove(agents, "po"))
}
