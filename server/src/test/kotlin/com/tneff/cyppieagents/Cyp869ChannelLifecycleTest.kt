package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.InMemoryReadCursorStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelMemberGrant
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.BadRequestException
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.ForbiddenException
import com.tneff.cyppieagents.routing.NotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-869 (OS-B) channel-lifecycle teeth — arbitrary DIRECT/GROUP orchestration channels beyond hub-and-spoke,
 * with the BINDING INVARIANT that the ONE [Hub.postAsAgent] `canWrite` chokepoint stays: a new channel is
 * unwritable-by-default, only members granted `canWrite` may send, and creating a channel adds NO write path.
 * Each tooth is red-provable vs a named mutant of the channel-lifecycle logic.
 */
class Cyp869ChannelLifecycleTest {
    private fun newHub(): Hub {
        val agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("backend", "BE", Role.WORKER, "backend"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
        )
        return Hub(HubState.hubAndSpoke(agents, HubState.OPERATOR_ID), InMemoryMessageStore(), readCursors = InMemoryReadCursorStore())
    }

    // Create seeds per-member ACL: a member granted canWrite CAN send, and the channel appears in the topology.
    // Mutant: createChannel seeds no ACL entries → the granted member's post 403s → reds.
    @Test fun createGrantsWriteToGrantedMember() {
        val hub = newHub()
        hub.createChannel("grp", "Group", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", canRead = true, canWrite = true)))
        assertTrue(hub.state.channels.any { it.id == "grp" }, "created channel appears in the topology")
        assertEquals("grp", hub.postAsAgent("backend", "grp", "hello").channelId, "a granted-canWrite member can send")
    }

    // ★ UNWRITABLE-BY-DEFAULT (the binding invariant): a READ-ONLY member and a NON-member cannot write — the
    // postAsAgent chokepoint denies. Mutant: createChannel grants canWrite to every member (or seeds a write for
    // non-members) → these posts succeed → reds. No channel opens a write path by default.
    @Test fun newChannelUnwritableByDefault_readOnlyAndNonMemberDenied() {
        val hub = newHub()
        hub.createChannel(
            "grp", "Group", ChannelKind.GROUP,
            listOf(ChannelMemberGrant("frontend", canRead = true, canWrite = false)), // read-only member
        )
        assertFailsWith<ForbiddenException>("a read-only member has no write access") {
            hub.postAsAgent("frontend", "grp", "nope")
        }
        assertFailsWith<ForbiddenException>("a non-member has no write access") {
            hub.postAsAgent("backend", "grp", "nope") // never granted → no entry → denied at the chokepoint
        }
    }

    // Non-spoke DIRECT addressing: a DIRECT channel {A,B} both-RW = worker↔worker direct, bypassing the PO hub;
    // the PO is NOT auto-a-member. Mutant: membership not synced from the grants → a member can't write → reds.
    @Test fun directChannelEnablesWorkerToWorker() {
        val hub = newHub()
        hub.createChannel(
            "be-fe", "BE-FE", ChannelKind.DIRECT,
            listOf(ChannelMemberGrant("backend", true, true), ChannelMemberGrant("frontend", true, true)),
        )
        hub.postAsAgent("backend", "be-fe", "hi frontend") // worker↔worker, no PO hub
        hub.postAsAgent("frontend", "be-fe", "hi backend")
        assertTrue(
            hub.state.acl.canWrite("be-fe", "backend") && hub.state.acl.canWrite("be-fe", "frontend"),
            "both DIRECT members can write (worker↔worker)",
        )
        assertTrue(!hub.state.acl.canWrite("be-fe", "po"), "the PO is NOT auto-a-member of an arbitrary DIRECT channel")
    }

    // Fail-closed: a HUB kind is forbidden via this API (spokes stay boot/addAgent-managed).
    // Mutant: drop the HUB guard → a HUB channel is minted here → reds.
    @Test fun createRejectsHubKind() {
        assertFailsWith<BadRequestException> { newHub().createChannel("x", "X", ChannelKind.HUB, emptyList()) }
    }

    // Fail-closed: a duplicate channel id is rejected.
    @Test fun createRejectsDuplicateId() {
        val hub = newHub()
        hub.createChannel("grp", "Group", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", true, true)))
        assertFailsWith<ConflictException> { hub.createChannel("grp", "Again", ChannelKind.GROUP, emptyList()) }
    }

    // Archive removes a DIRECT/GROUP channel from the active topology → gone from the list AND unwritable.
    // Mutant: archive leaves the channel in `channels` → still listed/writable → reds.
    @Test fun archiveRemovesChannelFromTopology() {
        val hub = newHub()
        hub.createChannel("grp", "Group", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", true, true)))
        hub.archiveChannel("grp")
        assertTrue(hub.state.channels.none { it.id == "grp" }, "archived channel is gone from the topology")
        assertFailsWith<ForbiddenException>("archived channel is unwritable (its ACL was removed)") {
            hub.postAsAgent("backend", "grp", "after archive")
        }
    }

    // ★ Fail-closed: a HUB spoke cannot be archived (would break hub-and-spoke coordination — the CYP-49 discipline).
    // Mutant: drop the HUB guard in archiveChannel → the spoke is removed → coordination breaks → reds.
    @Test fun archiveRejectsHubSpoke() {
        assertFailsWith<ConflictException> { newHub().archiveChannel("po-backend") } // a boot spoke
    }

    // Rename updates the display name; 404 on an unknown channel.
    @Test fun renameUpdatesNameAnd404sUnknown() {
        val hub = newHub()
        hub.createChannel("grp", "Old", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", true, true)))
        assertEquals("New", hub.renameChannel("grp", "New").name, "rename returns the refreshed channel")
        assertEquals("New", hub.state.channels.first { it.id == "grp" }.name, "the name is updated in the topology")
        assertFailsWith<NotFoundException> { hub.renameChannel("nope", "X") }
    }
}
