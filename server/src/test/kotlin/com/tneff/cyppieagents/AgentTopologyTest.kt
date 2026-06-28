package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime hub-topology mutation (S14 / CYP-97). The PO's #1 risk: add/remove must keep the ACL
 * fail-closed/deny-wins, stamp the new spoke with the right projectId (no cross-project leak), and
 * remove cleanly (no dangling channel/ACL).
 */
class AgentTopologyTest {

    private fun base(projectId: String = "alpha"): HubState {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
        )
        return HubState.hubAndSpoke(agents, HubState.OPERATOR_ID, projectId)
    }

    @Test
    fun addWorker_createsProjectScopedSpoke_withFailClosedAcl() {
        val state = base("alpha")
        state.addAgent(Agent("backend", "BE", Role.WORKER, "backend"))

        // New spoke exists, HUB kind, members [po, backend, operator], stamped with the active project.
        val spoke = state.channels.firstOrNull { it.id == "po-backend" }
        assertTrue(spoke != null && spoke.kind == ChannelKind.HUB)
        assertEquals("alpha", spoke!!.projectId, "new channel carries the active projectId (no cross-project leak)")
        assertEquals(setOf("po", "backend", HubState.OPERATOR_ID), spoke.members.toSet())

        // ACL: the new worker + the PO + the operator read/write its spoke...
        assertTrue(state.acl.canWrite("po-backend", "backend"))
        assertTrue(state.acl.canRead("po-backend", "backend"))
        assertTrue(state.acl.canWrite("po-backend", "po"))
        assertTrue(state.acl.canWrite("po-backend", HubState.OPERATOR_ID))
        // ...but a DIFFERENT worker cannot (fail-closed, deny-wins — cross-agent isolation).
        assertFalse(state.acl.canWrite("po-backend", "frontend"))
        assertFalse(state.acl.canRead("po-backend", "frontend"))
        assertTrue(state.agents.any { it.id == "backend" })
    }

    @Test
    fun newSpokeStampedWithActiveProject_notDefault() {
        val state = base("alpha")
        state.addAgent(Agent("backend", "BE", Role.WORKER, "backend"))
        // Mutation: addAgent stamping the channel/entries with DEFAULT_PROJECT_ID instead of activeProjectId
        // → out of scope in the "alpha" matrix → canWrite false → this assertion red.
        assertTrue(state.acl.canWrite("po-backend", "backend"), "spoke must be in the active project to be writable")
    }

    @Test
    fun removeWorker_isClean_noDanglingChannelOrAcl() {
        val state = base("alpha")
        state.addAgent(Agent("backend", "BE", Role.WORKER, "backend"))
        state.removeAgent("backend")

        // Channel gone, no entries reference it, agent gone, and the matrix denies everything on it.
        assertNull(state.channels.firstOrNull { it.id == "po-backend" }, "spoke channel removed")
        assertTrue(state.entries.none { it.channelId == "po-backend" }, "no dangling ACL entry")
        assertFalse(state.agents.any { it.id == "backend" })
        assertFalse(state.acl.canRead("po-backend", "backend"))
        assertFalse(state.acl.canWrite("po-backend", "backend"))
        // The PO's other spoke is untouched.
        assertTrue(state.acl.canWrite("po-frontend", "po"))
    }
}
