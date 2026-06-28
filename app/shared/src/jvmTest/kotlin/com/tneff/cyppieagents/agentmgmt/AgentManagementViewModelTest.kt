package com.tneff.cyppieagents.agentmgmt

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-86/87/88: the Agent-Management VM logic, over [StubAgentManagementRepository] on an Unconfined
 * scope (non-suspending stub → synchronous settle). Covers the reviewer gates — each mutation-provable:
 * - **fail-closed:** every open/confirm is a no-op without operator rights (drop the `editable` guard → RED).
 * - **guardrails visible before action:** PO blocked when one exists, id collision, only-PO unremovable,
 *   PO-role conflicts on edit — all derived in state, not post-hoc.
 * - **honest disclosure:** add creates a STOPPED agent (no spawn); edit sets the amber effect hint.
 * - **honest errors:** the server reason maps to the spec key.
 */
class AgentManagementViewModelTest {

    private fun vm(repo: AgentManagementRepository, editable: Boolean = true) =
        AgentManagementViewModel(repo, editable = editable, scope = CoroutineScope(Dispatchers.Unconfined))

    private fun agent(id: String, role: Role) = Agent(id, id.uppercase(), role, id, AgentRunState.RUNNING)

    private fun stub(vararg agents: Agent, deny: String? = null) =
        StubAgentManagementRepository(agents.toList(), denyWrites = deny)

    @Test
    fun load_populatesAgents() {
        val s = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER))).state.value
        assertFalse(s.loading)
        assertEquals(listOf("po", "fe"), s.agents.map { it.id })
        assertTrue(s.poExists)
    }

    // --- CYP-86 add ---

    @Test
    fun add_failClosed_withoutOperator() {
        val vm = vm(stub(agent("po", Role.PO)), editable = false)
        vm.openAdd()
        assertFalse(vm.state.value.addOpen) // no-op
    }

    @Test
    fun add_poBlocked_whenPoExists() {
        val vm = vm(stub(agent("po", Role.PO)))
        vm.openAdd()
        assertTrue(vm.state.value.addPoBlocked)
        vm.setAddId("x"); vm.setAddName("X"); vm.setAddRole(Role.PO)
        assertFalse(vm.state.value.canConfirmAdd) // PO blocked → cannot confirm
        vm.setAddRole(Role.WORKER)
        assertTrue(vm.state.value.canConfirmAdd) // WORKER is fine
    }

    @Test
    fun add_idCollision_blocksConfirm() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        vm.openAdd(); vm.setAddName("Dup"); vm.setAddId("fe")
        assertTrue(vm.state.value.addIdCollision)
        assertFalse(vm.state.value.canConfirmAdd)
    }

    @Test
    fun add_success_appendsStoppedAgent_andClosesDialog() {
        val vm = vm(stub(agent("po", Role.PO)))
        vm.openAdd(); vm.setAddId("be"); vm.setAddName("Backend"); vm.setAddRole(Role.WORKER)
        vm.confirmAdd()
        val s = vm.state.value
        assertFalse(s.addOpen)
        val added = s.agents.firstOrNull { it.id == "be" }
        assertEquals(AgentRunState.STOPPED, added?.runState) // created, not spawned
    }

    @Test
    fun add_serverReject_mapsErrorKey() {
        val vm = vm(stub(agent("po", Role.PO), deny = "agent_exists"))
        vm.openAdd(); vm.setAddId("new"); vm.setAddName("New"); vm.setAddRole(Role.WORKER)
        vm.confirmAdd()
        assertEquals("agent_add_id_exists", vm.state.value.addError)
    }

    // --- CYP-87 remove ---

    @Test
    fun remove_onlyPo_cannotConfirm() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        val po = vm.state.value.agents.first { it.id == "po" }
        assertTrue(vm.state.value.isOnlyPo(po))
        vm.openRemove(po)
        assertFalse(vm.state.value.canConfirmRemove) // the only PO is unremovable
    }

    @Test
    fun remove_worker_success_dropsFromList() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        val fe = vm.state.value.agents.first { it.id == "fe" }
        vm.openRemove(fe)
        assertTrue(vm.state.value.canConfirmRemove)
        vm.confirmRemove()
        assertNull(vm.state.value.removeTarget)
        assertFalse(vm.state.value.agents.any { it.id == "fe" })
    }

    @Test
    fun remove_failClosed_withoutOperator() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)), editable = false)
        vm.openRemove(vm.state.value.agents.first { it.id == "fe" })
        assertNull(vm.state.value.removeTarget) // no-op
    }

    @Test
    fun openRemove_defaultsToKeepWorktree_safeNotDestructive() {
        // Merge-gate pin (reviewer): the worktree fate of an irreversible delete defaults to the SAFE
        // KEEP — never pre-selected to the destructive DELETE. Mutation: default → DELETE turns this RED.
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        vm.openRemove(vm.state.value.agents.first { it.id == "fe" })
        assertEquals(WorktreeFate.KEEP, vm.state.value.removeWorktreeFate)
    }

    // --- CYP-88 edit ---

    @Test
    fun edit_success_setsAmberEffectHint_keepsDialogOpen() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        val fe = vm.state.value.agents.first { it.id == "fe" }
        vm.openEdit(fe)
        vm.setEditLaunch("claude --flag")
        vm.confirmEdit()
        val s = vm.state.value
        assertTrue(s.editEffectHint) // "saved ≠ active"
        assertEquals("fe", s.editTarget?.id) // dialog stays open to show the hint
    }

    @Test
    fun edit_poTakenByOther_blocksConfirm() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        val fe = vm.state.value.agents.first { it.id == "fe" }
        vm.openEdit(fe); vm.setEditRole(Role.PO) // another PO already exists
        assertTrue(vm.state.value.editPoTakenByOther)
        assertFalse(vm.state.value.canConfirmEdit)
    }

    @Test
    fun edit_wouldDropLastPo_blocksConfirm() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)))
        val po = vm.state.value.agents.first { it.id == "po" }
        vm.openEdit(po); vm.setEditRole(Role.WORKER) // rolling the only PO away
        assertTrue(vm.state.value.editWouldDropLastPo)
        assertFalse(vm.state.value.canConfirmEdit)
    }

    @Test
    fun edit_failClosed_withoutOperator() {
        val vm = vm(stub(agent("po", Role.PO), agent("fe", Role.WORKER)), editable = false)
        vm.openEdit(vm.state.value.agents.first { it.id == "fe" })
        assertNull(vm.state.value.editTarget) // no-op
    }
}
