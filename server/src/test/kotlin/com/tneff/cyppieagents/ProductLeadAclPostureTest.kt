package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-98: the Product-Lead ACL default posture is a **read-only reviewer**, enforced in the core ACL
 * (not just the UI). Four structural axes, each mutation-pinned:
 *  A1 canWrite=false everywhere (Hub.postAsAgent → 403);  A2 canRead=true on authorized spokes;
 *  A3 NO spoke of its own → structurally never a task target (spokeChannelFor == null);
 *  A4 the posture holds dynamically (runtime add of a PL, and a new worker added after a PL).
 */
class ProductLeadAclPostureTest {

    private val agents = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("pl", "Product Lead", Role.PRODUCT_LEAD, "pl"),
    )

    private fun state() = HubState.hubAndSpoke(agents, HubState.OPERATOR_ID, "default")

    @Test
    fun a1_productLeadCannotWriteToASpoke_coreEnforcedAnd403() {
        val st = state()
        assertFalse(st.acl.canWrite("po-frontend", "pl"), "PL canWrite=false at the core ACL")
        // And the Hub fail-closes the actual post (403) — the same chokepoint REST/mediation/MCP use.
        assertFailsWith<ForbiddenException> {
            Hub(st, InMemoryMessageStore()).postAsAgent("pl", "po-frontend", "I hereby instruct you to…")
        }
        // The worker on the same channel CAN write — the deny is PL-specific, not a broken channel.
        assertTrue(st.acl.canWrite("po-frontend", "frontend"))
    }

    @Test
    fun a2_productLeadCanReadAuthorizedSpokes() {
        val st = state()
        assertTrue(st.acl.canRead("po-frontend", "pl"), "PL is a read-only reviewer, not locked out")
        assertTrue(st.acl.isMember("po-frontend", "pl"))
    }

    @Test
    fun a3_productLeadHasNoSpoke_neverATaskTarget() {
        val st = state()
        assertNull(st.spokeChannelFor("pl"), "no po-pl spoke → mediation can never route a task to a PL")
        assertFalse(st.channels.any { it.id == "po-pl" }, "no spoke channel is created for a PL")
        // The worker DOES have a spoke (the build loop is PO/WORKER).
        assertEquals("po-frontend", st.spokeChannelFor("frontend"))
    }

    @Test
    fun a4_runtimeAddedProductLead_isReadOnlyOnEverySpoke() {
        // Boot with NO PL, then add one at runtime → read-only on the existing spoke, no spoke of its own.
        val st = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")),
            HubState.OPERATOR_ID, "default",
        )
        st.addAgent(Agent("pl", "PL", Role.PRODUCT_LEAD, "pl"))
        assertTrue(st.acl.canRead("po-frontend", "pl"))
        assertFalse(st.acl.canWrite("po-frontend", "pl"))
        assertNull(st.spokeChannelFor("pl"))

        // And a worker added AFTER the PL → the PL is read-only on the NEW spoke too (posture is consistent).
        st.addAgent(Agent("backend", "BE", Role.WORKER, "backend"))
        assertTrue(st.acl.canRead("po-backend", "pl"))
        assertFalse(st.acl.canWrite("po-backend", "pl"), "PL stays read-only on spokes created after it joined")
    }
}
