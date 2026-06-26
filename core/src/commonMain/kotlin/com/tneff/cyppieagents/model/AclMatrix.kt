package com.tneff.cyppieagents.model

/**
 * The ONE central ACL decision (Spec 02 §6.3), as a **pure** function over an immutable snapshot
 * of channels + entries — so the exact same logic is testable platform-neutrally in `:core`
 * commonTest and reused by every enforcement site (REST, WS, mediation) in `:server`.
 *
 * Decision = membership AND the per-(channel,agent) flag. Hub-and-spoke is just one assignment;
 * there is no special-casing here. Enforcement (fail-closed *before* a write, Reviewer Gate #2)
 * lives server-side but delegates the decision to this class — it never re-implements it.
 */
class AclMatrix(
    val channels: List<Channel>,
    val entries: List<AclEntry>,
) {
    private val membersByChannel: Map<String, Set<String>> =
        channels.associate { it.id to it.members.toSet() }

    // Group (not associateBy) so duplicate (channel,agent) entries are all considered. A silent
    // "last wins" could let an appended canWrite=true override an intended false (privilege
    // escalation). Defense in depth: deny wins on conflict (see [canRead]/[canWrite]).
    private val entriesByKey: Map<Pair<String, String>, List<AclEntry>> =
        entries.groupBy { it.channelId to it.agentId }

    fun isMember(channelId: String, agentId: String): Boolean =
        membersByChannel[channelId]?.contains(agentId) == true

    /**
     * Fail-closed: unknown channel, non-member, or no entry all yield false. On conflicting
     * duplicate entries, **deny wins** — every matching entry must grant read.
     */
    fun canRead(channelId: String, agentId: String): Boolean {
        if (!isMember(channelId, agentId)) return false
        val matching = entriesByKey[channelId to agentId] ?: return false
        return matching.all { it.canRead }
    }

    /**
     * Fail-closed: unknown channel, non-member, or no entry all yield false. On conflicting
     * duplicate entries, **deny wins** — every matching entry must grant write.
     */
    fun canWrite(channelId: String, agentId: String): Boolean {
        if (!isMember(channelId, agentId)) return false
        val matching = entriesByKey[channelId to agentId] ?: return false
        return matching.all { it.canWrite }
    }

    /** Channels this agent may receive from. */
    fun readableChannels(agentId: String): List<Channel> =
        channels.filter { canRead(it.id, agentId) }

    /** Filters a message list down to those in channels the agent may read (Spec 02 §6.3). */
    fun visibleMessages(agentId: String, messages: List<Message>): List<Message> =
        messages.filter { canRead(it.channelId, agentId) }
}
