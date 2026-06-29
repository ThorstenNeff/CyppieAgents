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

/**
 * CYP-111 (multi-project bug the E2E caught) — the PO-lockout guard ([HubState.setAcl], CYP-49) must
 * scope `poHubChannelIds()` to the ACTIVE project. With ≥2 projects, the FOREIGN project's hub channel
 * (e.g. `po-backend` while alpha is active) was absent from the active-scoped candidate matrix and read
 * as a lockout → a false 409 on EVERY ACL edit. Fixed by filtering the guard's hub-channel set to the
 * active project (like-for-like with the candidate matrix). The guard must STILL bite a real lockout in
 * the active project.
 */
class PoLockoutMultiProjectTest {

    private val op = HubState.OPERATOR_ID

    /** Two projects each with a hub spoke + the PO read+write on its OWN active hub channel. Active = alpha. */
    private fun state(): HubState {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        )
        val channels = listOf(
            Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend", op), projectId = "alpha"),
            Channel("po-backend", "po-backend", ChannelKind.HUB, listOf("po", "backend", op), projectId = "beta"),
        )
        val entries = buildList {
            // alpha hub channel
            add(AclEntry("po-frontend", "po", canRead = true, canWrite = true, projectId = "alpha"))
            add(AclEntry("po-frontend", "frontend", canRead = true, canWrite = true, projectId = "alpha"))
            add(AclEntry("po-frontend", op, canRead = true, canWrite = true, projectId = "alpha"))
            // beta hub channel (the foreign project's — must NOT count as a lockout when alpha is active)
            add(AclEntry("po-backend", "po", canRead = true, canWrite = true, projectId = "beta"))
            add(AclEntry("po-backend", "backend", canRead = true, canWrite = true, projectId = "beta"))
            add(AclEntry("po-backend", op, canRead = true, canWrite = true, projectId = "beta"))
        }
        return HubState(agents, channels, entries, activeProjectId = "alpha", operatorId = op)
    }

    @Test
    fun aclEdit_withTwoProjects_succeeds_noFalseLockout() {
        val s = state()
        // A legitimate worker toggle on the ACTIVE project's channel. The foreign `po-backend` (beta)
        // must not be read as a PO lockout. Under the bug this throws 409 `po_lockout_protected`.
        val applied = s.setAcl(AclEntry("po-frontend", "frontend", canRead = true, canWrite = false, projectId = "alpha"))
        assertEquals("po-frontend", applied.channelId)
        assertEquals(false, applied.canWrite, "the toggle was applied (no false 409)")
    }

    @Test
    fun realPoLockout_inActiveProject_still409() {
        val s = state()
        // Stripping the PO's write on its OWN active hub channel is a real lockout — the guard stays sharp.
        val e = assertFailsWith<ConflictException> {
            s.setAcl(AclEntry("po-frontend", "po", canRead = true, canWrite = false, projectId = "alpha"))
        }
        assertEquals("po_lockout_protected", e.code)
    }
}
