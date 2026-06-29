package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ConflictException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-112 — `setAcl` syncs `channel.members` (membership IS the per-agent ACL, CYP-93): an entry that
 * grants access makes the agent a member; a full revoke removes them. This unblocks the J3
 * cross-project grantee-READ (a `PUT /api/acl` entry on a shared channel now actually makes the grantee
 * a member of it), WITHOUT regressing same-project hub-and-spoke / PO-lockout.
 */
class SetAclMemberSyncTest {

    private val op = HubState.OPERATOR_ID

    // ---- cross-project grantee-READ (the J3 unblock) ----

    @Test
    fun granteeEntry_onSharedChannel_makesMember_unlocksRead() {
        // a-fe owned by alpha, shared INTO beta; active = beta. backend is NOT a member of a-fe yet.
        val channels = listOf(Channel("a-fe", "a-fe", ChannelKind.GROUP, listOf("frontend"), projectId = "alpha"))
        val entries = listOf(AclEntry("a-fe", "frontend", canRead = true, canWrite = true, projectId = "alpha"))
        val s = HubState(
            listOf(Agent("po", "PO", Role.PO, "po")), channels, entries,
            activeProjectId = "beta", operatorId = op,
        ) { pid -> if (pid == "beta") setOf("a-fe") else emptySet() }

        assertFalse(s.acl.canRead("a-fe", "backend"), "before provisioning: grantee is not a member → no read")

        // The `PUT /api/acl` analogue: a beta-stamped read entry for backend on the shared channel.
        s.setAcl(AclEntry("a-fe", "backend", canRead = true, canWrite = false, projectId = "beta"))

        assertTrue(s.acl.isMember("a-fe", "backend"), "the granting entry made backend a member")
        assertTrue(s.acl.canRead("a-fe", "backend"), "J3: grantee can now READ the shared channel")
    }

    // ---- same-project: no regress ----

    /** Single project (alpha), one hub spoke; PO + frontend r+w, operator member. Active = alpha. */
    private fun sameProject(): HubState {
        val agents = listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend"))
        val channels = listOf(Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend", op), projectId = "alpha"))
        val entries = listOf(
            AclEntry("po-frontend", "po", canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("po-frontend", "frontend", canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("po-frontend", op, canRead = true, canWrite = true, projectId = "alpha"),
        )
        return HubState(agents, channels, entries, activeProjectId = "alpha", operatorId = op)
    }

    @Test
    fun workerToggle_keepsMembership() {
        val s = sameProject()
        // a legitimate read-only toggle: still grants access → stays a member.
        s.setAcl(AclEntry("po-frontend", "frontend", canRead = true, canWrite = false, projectId = "alpha"))
        assertTrue(s.acl.isMember("po-frontend", "frontend"), "a worker toggle that keeps read stays a member")
        assertTrue(s.acl.canRead("po-frontend", "frontend"))
        assertFalse(s.acl.canWrite("po-frontend", "frontend"), "the toggle applied")
    }

    @Test
    fun fullRevoke_removesMembership() {
        val s = sameProject()
        s.setAcl(AclEntry("po-frontend", "frontend", canRead = false, canWrite = false, projectId = "alpha"))
        assertFalse(s.acl.isMember("po-frontend", "frontend"), "a full revoke (no access) removes membership")
        assertFalse(s.acl.canRead("po-frontend", "frontend"))
    }

    @Test
    fun revokingPoOnItsHubChannel_still409() {
        val s = sameProject()
        // Removing the PO's access on its own hub channel removes it from members → candidate shows the
        // lockout → 409. The guard stays sharp; nothing is committed.
        val e = assertFailsWith<ConflictException> {
            s.setAcl(AclEntry("po-frontend", "po", canRead = false, canWrite = false, projectId = "alpha"))
        }
        assertEquals("po_lockout_protected", e.code)
        // fail-closed: the PO is still a member + can read/write (nothing committed).
        assertTrue(s.acl.canWrite("po-frontend", "po"), "rejected change committed nothing")
    }
}
