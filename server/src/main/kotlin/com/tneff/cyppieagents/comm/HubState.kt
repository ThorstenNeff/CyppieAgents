package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Role

/**
 * Mutable hub configuration (agents, channels, ACL entries) and the derived [AclMatrix].
 * The matrix is rebuilt on every ACL change so enforcement always reads a consistent snapshot;
 * the *decision* itself lives only in [AclMatrix] (single source).
 */
class HubState(
    val agents: List<Agent>,
    initialChannels: List<Channel>,
    initialEntries: List<AclEntry>,
) {
    private val lock = Any()

    @Volatile
    var channels: List<Channel> = initialChannels
        private set

    @Volatile
    var entries: List<AclEntry> = initialEntries
        private set

    @Volatile
    var acl: AclMatrix = AclMatrix(initialChannels, initialEntries)
        private set

    fun agent(id: String): Agent? = agents.firstOrNull { it.id == id }

    /** Upsert one ACL entry (operator action) and rebuild the matrix. */
    fun setAcl(entry: AclEntry): AclEntry = synchronized(lock) {
        val next = entries.filterNot { it.channelId == entry.channelId && it.agentId == entry.agentId } + entry
        entries = next
        acl = AclMatrix(channels, next)
        entry
    }

    /** The agent's hub-and-spoke channel (`po-<agentId>`), if present and the agent is a member. */
    fun spokeChannelFor(agentId: String): String? =
        channels.firstOrNull { it.id == "po-$agentId" && agentId in it.members }?.id

    companion object {
        /**
         * Builds the default hub-and-spoke topology from the agent list (Spec 02 §6.2):
         * one `po-<worker>` channel per worker, both members read+write. PO is in every spoke;
         * a worker only in its own. Hub-and-spoke = this ACL assignment, no special logic.
         */
        fun hubAndSpoke(agents: List<Agent>): HubState {
            val po = agents.firstOrNull { it.role == Role.PO }
                ?: error("hub-and-spoke requires exactly one PO agent")
            val workers = agents.filter { it.role == Role.WORKER }
            val channels = workers.map { w ->
                Channel(
                    id = "po-${w.id}",
                    name = "po-${w.id}",
                    kind = com.tneff.cyppieagents.model.ChannelKind.HUB,
                    members = listOf(po.id, w.id),
                )
            }
            val entries = channels.flatMap { ch ->
                ch.members.map { AclEntry(ch.id, it, canRead = true, canWrite = true) }
            }
            return HubState(agents, channels, entries)
        }
    }
}
