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
    private val entryByKey: Map<Pair<String, String>, AclEntry> =
        entries.associateBy { it.channelId to it.agentId }

    fun isMember(channelId: String, agentId: String): Boolean =
        membersByChannel[channelId]?.contains(agentId) == true

    /** Fail-closed: unknown channel, non-member, or missing/false entry all yield false. */
    fun canRead(channelId: String, agentId: String): Boolean =
        isMember(channelId, agentId) && entryByKey[channelId to agentId]?.canRead == true

    /** Fail-closed: unknown channel, non-member, or missing/false entry all yield false. */
    fun canWrite(channelId: String, agentId: String): Boolean =
        isMember(channelId, agentId) && entryByKey[channelId to agentId]?.canWrite == true

    /** Channels this agent may receive from. */
    fun readableChannels(agentId: String): List<Channel> =
        channels.filter { canRead(it.id, agentId) }

    /** Filters a message list down to those in channels the agent may read (Spec 02 §6.3). */
    fun visibleMessages(agentId: String, messages: List<Message>): List<Message> =
        messages.filter { canRead(it.channelId, agentId) }
}
