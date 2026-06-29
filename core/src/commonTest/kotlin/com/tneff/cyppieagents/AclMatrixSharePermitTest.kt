package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S17 / CYP-93 — the cross-project PERMIT at the `AclMatrix` chokepoint: an authorized shared channel
 * reaches INTO the active project, widening to the channel's explicit members ONLY (read via their own
 * active-stamped entry), never the foreign project. Pure, so the core safety axes are pinned in `:core`:
 *  - **sibling-no-leak:** a non-shared sibling channel of the same owner stays invisible.
 *  - **non-member-no-see:** a grantee-project agent that is NOT a member can't read the shared channel.
 *  - **revoke → fail-closed:** drop the channel from the shared set and it falls back to exact-match,
 *    invisible, **even with the grantee's AclEntry still present** (the share is the gate, not the entry).
 */
class AclMatrixSharePermitTest {

    // Channel C (owner alpha) shared INTO beta; sibling C2 (owner alpha) NOT shared. b1 is a member of
    // BOTH with a beta-stamped read entry on each — so ONLY the per-channelId keying (not "owner alpha
    // shared anything") keeps the non-shared sibling C2 out of beta's reach. (This is the realistic
    // over-widen vector: a grantee must read the SHARED channel, never the owner's other channels.)
    private val channels = listOf(
        Channel("c", "c", ChannelKind.GROUP, members = listOf("a1", "b1"), projectId = "alpha"),
        Channel("c2", "c2", ChannelKind.GROUP, members = listOf("a1", "b1"), projectId = "alpha"),
    )
    private val entries = listOf(
        AclEntry("c", "a1", canRead = true, canWrite = true, projectId = "alpha"),
        AclEntry("c", "b1", canRead = true, canWrite = false, projectId = "beta"),
        AclEntry("c2", "a1", canRead = true, canWrite = true, projectId = "alpha"),
        AclEntry("c2", "b1", canRead = true, canWrite = false, projectId = "beta"), // entry exists, but c2 is NOT shared
    )

    /** Active = beta (the grantee). [shared] = the authorized inbound shares. */
    private fun matrix(shared: Set<String>) =
        AclMatrix(channels, entries, activeProjectId = "beta", sharedInboundChannelIds = shared)

    @Test fun sharedChannel_visibleToGranteeMember_siblingNotLeaked() {
        val m = matrix(setOf("c"))
        assertTrue(m.canRead("c", "b1"), "grantee reads the SHARED channel via its own beta-stamped entry")
        // sibling-no-leak: b1 even has a beta entry + membership on c2, but c2 is NOT shared → unreadable.
        // Only the per-channelId keying gives this; a "owner alpha shared anything → widen all" leaks c2.
        assertFalse(m.canRead("c2", "b1"), "non-shared sibling c2 is unreadable despite the grantee's entry")
        assertEquals(listOf("c"), m.readableChannels("b1").map { it.id }, "grantee reaches ONLY the shared channel")
        // ENTRIES are NOT OR'd by the share — only the CHANNEL visibility is (the per-agent grantee reads
        // via its OWN active-stamped entry). Every visible entry carries the ACTIVE project; an owner
        // (alpha) entry on the shared channel must never egress into beta's scope (CYP-81 egress class).
        // Mutation: `entries.filter { permits || channelId in shared }` → an alpha entry on c leaks → red.
        assertTrue(m.entries.all { it.projectId == "beta" }, "no foreign-project AclEntry egresses via the share")
    }

    @Test fun nonMember_cannotSeeSharedChannel() {
        val m = matrix(setOf("c"))
        // b2 is not a member of c and has no entry → no read even though c is shared into beta.
        assertFalse(m.canRead("c", "b2"), "non-member grantee agent cannot read the shared channel")
        assertTrue(m.readableChannels("b2").isEmpty(), "non-member sees nothing")
    }

    @Test fun revoke_failsClosed_evenWithLingeringEntry() {
        // The grantee entry (c, b1, beta) is STILL present, but the share is revoked (empty set).
        val m = matrix(emptySet())
        assertFalse(m.canRead("c", "b1"), "revoke → channel falls back to exact-match (alpha≠beta), invisible")
        assertTrue(m.readableChannels("b1").isEmpty(), "no lingering-entry access after revoke (share is the gate)")
    }

    @Test fun sharedChannelMessages_visibleAcrossBoundary_butNotSiblings() {
        val m = matrix(setOf("c"))
        val msgs = listOf(
            Message("m1", "c", from = "a1", body = "x", ts = 1, projectId = "alpha"),  // owner's msg in shared channel
            Message("m2", "c2", from = "a1", body = "y", ts = 2, projectId = "alpha"), // sibling channel msg
        )
        val visible = m.visibleMessages("b1", msgs).map { it.id }
        assertEquals(listOf("m1"), visible, "grantee sees the shared channel's cross-boundary message, NOT the sibling's")
    }
}
