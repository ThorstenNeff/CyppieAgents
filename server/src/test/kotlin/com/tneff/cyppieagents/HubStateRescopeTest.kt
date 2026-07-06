package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S13 / CYP-102 — `HubState.rescope` re-scopes the comm chokepoint to the active project. The comm
 * read-paths (`/api/channels`·`/inbox`·`/acl`·`/ws/comm`) all read `state.acl` (the project-scoped
 * AclMatrix, CYP-81). A project switch must rebuild that matrix so the view follows the active pointer
 * WITHOUT a restart, and never leak another project's channels/entries.
 *
 * Built with TWO channels stamped for different projects so one test proves both no-cross-project
 * (only the active project's channel is visible) and switch-flips-view (rescope swaps the visible set).
 * The PO-pinned mutant — `rescope` flips the pointer but skips the matrix rebuild — reddens here.
 */
class HubStateRescopeTest {

    private val op = HubState.OPERATOR_ID

    private fun state(): HubState {
        val chA = Channel("ca", "ca", ChannelKind.GROUP, listOf(op), projectId = "alpha")
        val chB = Channel("cb", "cb", ChannelKind.GROUP, listOf(op), projectId = "beta")
        val entries = listOf(
            AclEntry("ca", op, canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("cb", op, canRead = true, canWrite = true, projectId = "beta"),
        )
        return HubState(emptyList(), listOf(chA, chB), entries, activeProjectId = "alpha", operatorId = op)
    }

    @Test fun activeProjectsChannelOnly_visibleAtStart() {
        val s = state()
        assertEquals(listOf("ca"), s.acl.readableChannels(op).map { it.id }, "no-cross-project: only alpha's channel")
        assertTrue(s.acl.canRead("ca", op))
        assertFalse(s.acl.canRead("cb", op), "beta channel not readable while alpha is active")
        // /api/acl reads state.acl.entries — also project-scoped (egress).
        assertEquals(listOf("ca"), s.acl.entries.map { it.channelId }, "only alpha's ACL entry egresses")
    }

    @Test fun rescope_flipsTheVisibleSet_withoutRestart() {
        val s = state()
        s.rescope("beta")
        assertEquals("beta", s.activeProjectId, "pointer flipped")
        assertEquals(listOf("cb"), s.acl.readableChannels(op).map { it.id }, "switch flips view: now only beta's channel")
        assertTrue(s.acl.canRead("cb", op))
        assertFalse(s.acl.canRead("ca", op), "alpha no longer readable after switch")
        assertEquals(listOf("cb"), s.acl.entries.map { it.channelId }, "ACL entries re-scoped to beta")
        // and back
        s.rescope("alpha")
        assertEquals(listOf("ca"), s.acl.readableChannels(op).map { it.id }, "rescoping back restores alpha")
    }

    @Test fun rescope_swapsAgentSet_freshProjectEmpty_thenSwitchBackRestores() {
        // CYP-246: the agent SET is per-project (the piece the matrix could not isolate — Agent has no
        // projectId, and GET /api/agents reads state.agents directly). Seed alpha with two agents; a switch
        // to a FRESH project (beta, never seeded) must yield 0 agents — NOT leak alpha's set (the reported
        // bug). Switching back restores them (loss-free). The mutant "rescope skips the agent-list swap"
        // reddens here (beta would keep alpha's two agents).
        val chA = Channel("ca", "ca", ChannelKind.GROUP, listOf(op), projectId = "alpha")
        val agents = listOf(Agent("po", "PO", Role.PO, "po"), Agent("backend", "Backend", Role.WORKER, "backend"))
        val entries = listOf(AclEntry("ca", op, canRead = true, canWrite = true, projectId = "alpha"))
        val s = HubState(agents, listOf(chA), entries, activeProjectId = "alpha", operatorId = op)
        assertEquals(listOf("po", "backend"), s.agents.map { it.id }, "alpha starts with its two agents")

        s.rescope("beta")
        assertTrue(s.agents.isEmpty(), "fresh project beta → 0 agents (no leak of alpha's set — the reported bug)")

        s.rescope("alpha")
        assertEquals(listOf("po", "backend"), s.agents.map { it.id }, "switching back restores alpha's agents (loss-free)")
    }

    @Test fun blankRescope_failsClosed_empty() {
        val s = state()
        s.rescope("") // misconfiguration / blank active
        assertTrue(s.acl.readableChannels(op).isEmpty(), "fail-closed: blank active shows nothing, never all")
        assertTrue(s.acl.entries.isEmpty(), "fail-closed: no entry egresses on blank active")
    }
}
