package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-884 (OS-D, Compose mirror of web-ts CYP-876 `agentAddressing.test.ts`) — [resolveAgentChannel] is fail-closed:
 * 1 DIRECT spoke → Resolved; >1 → Ambiguous (NEVER silent-first); 0 → Unreachable (NEVER a fabricated channel). Only
 * DIRECT counts — a GROUP/HUB membership is not a DM spoke.
 */
class AgentAddressingTest {

    private fun ch(id: String, kind: ChannelKind, members: List<String>) = Channel(id, id, kind, members)
    private val spokeFE = ch("po-frontend", ChannelKind.DIRECT, listOf("po", "frontend"))
    private val spokeBE = ch("po-backend", ChannelKind.DIRECT, listOf("po", "backend"))
    private val group = ch("team", ChannelKind.GROUP, listOf("po", "frontend", "backend"))
    private val hub = ch("hub", ChannelKind.HUB, listOf("po", "frontend", "backend"))

    @Test
    fun exactlyOneDirectSpoke_resolvesToThatChannel() {
        assertEquals(AgentAddress.Resolved("po-frontend"), resolveAgentChannel("frontend", listOf(spokeFE, spokeBE, group, hub)))
    }

    @Test
    fun noDirectSpoke_isUnreachable_neverFabricated() {
        // A GROUP/HUB membership is NOT a DM spoke. MUT: fall back to a GROUP/HUB (fabricate a DM route) → reds.
        assertEquals(AgentAddress.Unreachable, resolveAgentChannel("frontend", listOf(group, hub)))
        assertEquals(AgentAddress.Unreachable, resolveAgentChannel("nobody", listOf(spokeFE, spokeBE)))
    }

    @Test
    fun moreThanOneDirectSpoke_isAmbiguous_neverSilentFirst() {
        // MUT: return Resolved(spokes[0]) (silent-first pick) → reds. The ambiguity is surfaced, not guessed away.
        val dupA = ch("po-frontend", ChannelKind.DIRECT, listOf("po", "frontend"))
        val dupB = ch("po-frontend-2", ChannelKind.DIRECT, listOf("po", "frontend"))
        assertEquals(AgentAddress.Ambiguous(listOf("po-frontend", "po-frontend-2")), resolveAgentChannel("frontend", listOf(dupA, dupB)))
    }

    @Test
    fun onlyDirectCounts_groupOrHubWithMember_isNotASpoke() {
        assertEquals(AgentAddress.Unreachable, resolveAgentChannel("frontend", listOf(group)))
        assertEquals(AgentAddress.Unreachable, resolveAgentChannel("frontend", listOf(hub)))
    }
}
