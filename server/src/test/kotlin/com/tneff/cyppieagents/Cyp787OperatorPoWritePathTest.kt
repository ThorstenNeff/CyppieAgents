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
 * CYP-787 (C1/1b, variant **(a) symmetric**) — the Operator→PO write path. The PO gains a dedicated
 * DIRECT operator↔PO channel `op-po` (members {operator, po}, both canRead+canWrite). This makes the PO a
 * real spoke like a worker: [HubState.spokeChannelFor] resolves the PO to `op-po`, so BOTH the send side
 * ([Hub.writableAgents] → the operator may task the PO) AND the MediationRouter (the PO's turn-output routes
 * back to `op-po`) agree. It narrowly amends CYP-98 "PO=hub, never a task target" to this ONE operator-inbound
 * edge — **workers are NOT members of `op-po`, so a worker still cannot task the PO** (the narrow flip).
 *
 * Teeth here are the HubState/Hub-level invariants (topology + ACL + resolution + writability). The
 * MediationRouter output-routing tooth (the (a)-specific "PO reply lands in op-po, not dropped") lives in
 * [MediationRoutingTest]; the HTTP composer-enable parity lives in the CYP-779 routes test.
 */
class Cyp787OperatorPoWritePathTest {

    private val agents = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    private val op = HubState.OPERATOR_ID
    private val opPo = HubState.OP_PO_CHANNEL_ID

    private fun withOperator() = HubState.hubAndSpoke(agents, operatorId = op)
    private fun hub() = Hub(withOperator(), InMemoryMessageStore())

    @Test
    fun opPoChannel_isSeeded_directKind_membersOperatorAndPo() {
        val state = withOperator()
        val ch = state.channels.firstOrNull { it.id == opPo }
        assertTrue(ch != null, "op-po must be seeded when an operator exists")
        assertEquals(ChannelKind.DIRECT, ch!!.kind, "op-po is DIRECT (not a HUB spoke) — keeps CYP-111 clear")
        assertEquals(setOf(op, "po"), ch.members.toSet(), "op-po members are exactly {operator, po} — no worker")
    }

    @Test
    fun opPoAcl_operatorAndPo_bothReadWrite_symmetric() {
        val acl = withOperator().acl
        // (a) symmetric: operator tasks the PO AND the PO writes its reply back — both RW on the one channel.
        assertTrue(acl.canWrite(opPo, op), "operator canWrite op-po (tasks the PO)")
        assertTrue(acl.canRead(opPo, op), "operator canRead op-po")
        assertTrue(acl.canWrite(opPo, "po"), "PO canWrite op-po — the (a) mechanism (PO reply routes here)")
        assertTrue(acl.canRead(opPo, "po"), "PO canRead op-po (receives the operator's task)")
    }

    @Test
    fun spokeChannelFor_po_resolvesOpPo_workersUnchanged() {
        val state = withOperator()
        assertEquals(opPo, state.spokeChannelFor("po"), "the PO's spoke is op-po (CYP-787 generalization)")
        assertEquals("po-frontend", state.spokeChannelFor("frontend"), "a worker's spoke is unchanged")
        assertEquals("po-backend", state.spokeChannelFor("backend"), "a worker's spoke is unchanged")
    }

    @Test
    fun poHubChannelIds_excludesOpPo_cyp111GuardStaysClear() {
        val ids = withOperator().poHubChannelIds()
        assertFalse(opPo in ids, "op-po is DIRECT → NOT a PO-hub channel → CYP-111 lockout guard never trips on it")
        assertTrue("po-frontend" in ids && "po-backend" in ids, "the real HUB spokes are still guarded")
    }

    @Test
    fun writableAgents_operator_includesPo_viaOpPo() {
        assertTrue("po" in hub().writableAgents(op), "the operator may now task the PO (op-po, canWrite)")
    }

    @Test
    fun writableAgents_worker_stillExcludesPo_theNarrowFlip() {
        // The invariant amendment is NARROW: only the operator got the inbound edge. A worker is not a member
        // of op-po → cannot write it → the PO is NOT a send target for a worker (CYP-98 holds for workers).
        assertFalse("po" in hub().writableAgents("frontend"), "a worker still cannot task the PO")
        assertFalse("po" in hub().writableAgents("backend"), "a worker still cannot task the PO")
    }

    @Test
    fun po_writeFootprint_isWorkerSpokesPlusOpPo_only_noBroadGrant() {
        // The op-po grant is the PO's SOLE new write edge. Its full writable set = its worker-spoke memberships
        // (pre-existing) + op-po. A broad/leaked grant would change this set → red.
        assertEquals(setOf("po-frontend", "po-backend", opPo), hub().writableChannels("po").toSet(),
            "the PO writes exactly its worker spokes + op-po — no broader grant")
    }

    @Test
    fun noOperator_noOpPo_poHasNoSpoke_failClosed() {
        // Seeded ONLY with an operator: no operator ⇒ nobody can task the PO ⇒ no op-po, PO spoke stays null.
        val state = HubState.hubAndSpoke(agents)
        assertNull(state.channels.firstOrNull { it.id == opPo }, "no operator → no op-po channel")
        assertNull(state.spokeChannelFor("po"), "no op-po → the PO has no spoke (unchanged pre-787 behaviour)")
    }
}
