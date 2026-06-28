package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentMgmtGuard
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    // ---- remove ----

    @Test fun remove_worker_ok() = assertNull(AgentMgmtGuard.validateRemove(agents, "frontend"))

    @Test fun remove_unknown_agentNotFound() =
        assertEquals("agent_not_found", AgentMgmtGuard.validateRemove(agents, "ghost"))

    @Test fun remove_onlyPo_lastPo() =
        assertEquals("last_po", AgentMgmtGuard.validateRemove(agents, "po"))
}
