package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind

/**
 * CYP-884 (OS-D, Compose mirror of web-ts CYP-876 `agentAddressing.ts`) — client-side recipient addressing: resolve a
 * target agentId to its DIRECT/spoke channel. A spoke = a DIRECT channel whose members include the target; the visible
 * channels are already ACL-filtered to the PO/operator, so the target's DIRECT channel IS the PO↔target spoke. No
 * `:server` primitive (client-side only).
 *
 * Fail-closed, render ≠ authority:
 *  • exactly one DIRECT spoke → [AgentAddress.Resolved].
 *  • MORE than one → [AgentAddress.Ambiguous]: NEVER silent-first. A DM-routing decision derived from untrusted server
 *    data must not be guessed away; the ambiguity is itself a signal (possibly corrupt data) and is surfaced.
 *  • none → [AgentAddress.Unreachable]: honestly "not directly addressable" — NO fabricated channel (a new direct link
 *    is OS-C channel-creation, not this).
 */
sealed interface AgentAddress {
    /** Exactly one DIRECT spoke — address it. */
    data class Resolved(val channelId: String) : AgentAddress

    /** >1 DIRECT spoke — surfaced as a flag; the action is disabled (never a silent-first pick). */
    data class Ambiguous(val channelIds: List<String>) : AgentAddress

    /** No DIRECT spoke — honestly not directly addressable; never a fabricated channel. */
    data object Unreachable : AgentAddress
}

/**
 * Resolve [agentId] to its DIRECT spoke among the (ACL-filtered) [channels]. Never fabricates and never silently
 * picks: 1 spoke → [AgentAddress.Resolved]; >1 → [AgentAddress.Ambiguous] (all candidates, for the flag); 0 →
 * [AgentAddress.Unreachable]. Only DIRECT counts — a GROUP/HUB membership is NOT a DM spoke.
 */
fun resolveAgentChannel(agentId: String, channels: List<Channel>): AgentAddress {
    val spokes = channels.filter { it.kind == ChannelKind.DIRECT && agentId in it.members }
    return when {
        spokes.size == 1 -> AgentAddress.Resolved(spokes.first().id)
        spokes.size > 1 -> AgentAddress.Ambiguous(spokes.map { it.id })
        else -> AgentAddress.Unreachable
    }
}
