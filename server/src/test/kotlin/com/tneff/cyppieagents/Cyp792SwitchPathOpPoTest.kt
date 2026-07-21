package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-792 (CYP-787 follow-up) — op-po in the multi-project switch/rescope path. `HubState.hubAndSpoke` (the boot
 * op-po seed) is boot-only (2 call sites); a non-boot/switched-to project rehydrates its agents via
 * [com.tneff.cyppieagents.boot.BootOrchestrator]'s `state.addAgent(...)`, which BYPASSES the exactly-one-PO
 * add-guard. Before this fix `addAgent` had no `Role.PO` branch → a rehydrated PO got NO op-po → its window
 * regressed to read-only (the CYP-787 symptom returning in the S12 case). The fix single-sources op-po into the
 * PO-add path so EVERY PO (boot / rehydration / future multi-project create) gets it, no drift.
 *
 * These teeth simulate the rehydration slice directly: an operator-scoped [HubState] whose active slice has NO
 * op-po yet (as after a rescope, before the PO is re-added), then `addAgent(PO)` — the exact BootOrchestrator path.
 */
class Cyp792SwitchPathOpPoTest {

    private val op = HubState.OPERATOR_ID
    private val opPo = HubState.OP_PO_CHANNEL_ID

    /** A freshly-rescoped, empty project slice that HAS an operator (mirrors the switch/rehydration precondition). */
    private fun emptyOperatorSlice() = HubState(emptyList(), emptyList(), emptyList(), operatorId = op)

    @Test
    fun poReAddedViaAddAgent_seedsOpPo_directBothReadWrite() {
        val state = emptyOperatorSlice()
        assertNull(state.spokeChannelFor("po"), "pre: the rehydrated slice has no op-po yet")

        state.addAgent(Agent("po", "PO", Role.PO, "po")) // the BootOrchestrator rehydration path

        val ch = state.channels.firstOrNull { it.id == opPo }
        assertTrue(ch != null, "CYP-792: addAgent(PO) seeds op-po in the switch/rescope path")
        assertEquals(ChannelKind.DIRECT, ch!!.kind, "op-po is DIRECT (CYP-111 stays clear)")
        assertEquals(setOf(op, "po"), ch.members.toSet(), "members {operator, po}")
        assertTrue(state.acl.canWrite(opPo, op) && state.acl.canWrite(opPo, "po"), "both RW (a-symmetric)")
        assertEquals(opPo, state.spokeChannelFor("po"), "the rehydrated PO now resolves to op-po")
    }

    @Test
    fun switchedToPo_isWritableForOperator() {
        // The load-bearing tooth: after the switch/rehydration path adds the PO, the operator may task it.
        // Mutation: drop the addAgent Role.PO branch → no op-po → "po" ∉ writableAgents → RED.
        val state = emptyOperatorSlice().also { it.addAgent(Agent("po", "PO", Role.PO, "po")) }
        val hub = Hub(state, InMemoryMessageStore())
        assertTrue("po" in hub.writableAgents(op), "CYP-792: a switched-to project's PO is writable for the operator")
    }

    @Test
    fun poReAdd_isIdempotent_noSecondOpPo() {
        // Re-adding a PO (or a slice that already carries op-po) must not create a duplicate op-po.
        val state = emptyOperatorSlice().also { it.addAgent(Agent("po", "PO", Role.PO, "po")) }
        state.addAgent(Agent("po2", "PO2", Role.PO, "po2")) // idempotency guard: op-po already present
        assertEquals(1, state.channels.count { it.id == opPo }, "exactly one op-po, never duplicated")
    }

    @Test
    fun noOperator_addedPo_getsNoOpPo_failClosedFloor() {
        // Without an operator there is nobody to task the PO → no op-po (mirrors hubAndSpoke's operator gate).
        val state = HubState(emptyList(), emptyList(), emptyList(), operatorId = null)
        state.addAgent(Agent("po", "PO", Role.PO, "po"))
        assertNull(state.channels.firstOrNull { it.id == opPo }, "no operator → no op-po even for an added PO")
        assertNull(state.spokeChannelFor("po"))
    }

    @Test
    fun opPoSeed_isProjectScoped_notBlockedByAnotherProjectsOpPo() {
        // The CYP-81 cross-project-constant-id trap: op-po's id is a shared constant and `channels` holds EVERY
        // project's channels un-stashed (rescope isolates by projectId, does not swap the list). Seeding a
        // SECOND project's op-po must not be refused because the FIRST project's op-po is present. This is the
        // bug the e2e switch journey caught that a fresh-slice unit test could not. Mutation: drop the
        // `&& it.projectId == activeProjectId` from the addAgent guard → the 2nd project's PO gets no op-po → RED.
        val state = HubState(emptyList(), emptyList(), emptyList(), operatorId = op)
        state.addAgent(Agent("po", "PO", Role.PO, "po"))          // project DEFAULT → its own op-po
        state.rescope("proj-2")                                    // switch: channels still holds DEFAULT's op-po
        state.addAgent(Agent("po", "PO", Role.PO, "po"))          // proj-2's PO must still get an op-po
        assertEquals(2, state.channels.count { it.id == opPo }, "each project seeds its OWN op-po (project-scoped)")
        assertEquals(opPo, state.spokeChannelFor("po"), "the active project's PO still resolves to op-po")
    }

    @Test
    fun addedPo_getsNoWorkerSpoke_itIsTheHub() {
        // A PO is the hub, not a task target of a `po-<id>` spoke — it gets op-po, never `po-po`.
        val state = emptyOperatorSlice().also { it.addAgent(Agent("po", "PO", Role.PO, "po")) }
        assertFalse(state.channels.any { it.id == "po-po" }, "a PO never gets a worker spoke po-<id>")
    }
}
