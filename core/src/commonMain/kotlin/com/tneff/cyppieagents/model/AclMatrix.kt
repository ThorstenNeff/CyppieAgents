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
    channels: List<Channel>,
    entries: List<AclEntry>,
    /** Active tenant (S12 / CYP-81). Defaulted so existing 2-arg constructions and pre-S12 payloads
        run in the single MVP project unchanged. */
    val activeProjectId: String = DEFAULT_PROJECT_ID,
) {
    // Project scoping is folded in HERE — the single ACL chokepoint (S12 / CYP-81). Only channels and
    // entries in the active project form the decision surface; out-of-scope ones (blank or mismatched
    // projectId, via [ProjectScope]) are dropped, so isMember/canRead/canWrite see them as absent →
    // DENY, composed with the ACL flags (deny-wins). There is no "global" bucket, so a request that
    // names another project's channel fails closed rather than falling through to open. MVP=1:
    // everything is in the one project, so the decision is byte-for-byte unchanged.
    val channels: List<Channel> = channels.filter { ProjectScope.permits(it.projectId, activeProjectId) }
    val entries: List<AclEntry> = entries.filter { ProjectScope.permits(it.projectId, activeProjectId) }

    private val membersByChannel: Map<String, Set<String>> =
        this.channels.associate { it.id to it.members.toSet() }

    // Group (not associateBy) so duplicate (channel,agent) entries are all considered. A silent
    // "last wins" could let an appended canWrite=true override an intended false (privilege
    // escalation). Defense in depth: deny wins on conflict (see [canRead]/[canWrite]).
    private val entriesByKey: Map<Pair<String, String>, List<AclEntry>> =
        this.entries.groupBy { it.channelId to it.agentId }

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

    /**
     * Filters a message list down to those the agent may read (Spec 02 §6.3): in the active project
     * AND in a channel the agent may read. The project gate is applied first (S12 / CYP-81), so a
     * message tagged with another/blank project is never returned even if its channel id collides
     * with an in-scope one — fail-closed, deny-wins.
     */
    fun visibleMessages(agentId: String, messages: List<Message>): List<Message> =
        messages.filter { ProjectScope.permits(it.projectId, activeProjectId) && canRead(it.channelId, agentId) }
}
