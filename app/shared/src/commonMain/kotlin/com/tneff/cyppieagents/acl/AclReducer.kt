package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role

/** Which grant a toggle targets. */
enum class AclDimension { READ, WRITE }

/**
 * One matrix cell's **structural** state (CYP-19 §3) — the effective grant via the authoritative
 * `:core` [AclMatrix] (fail-closed, deny-wins), membership as its own axis, conflict + PO-critical
 * markers. Transient UI states (pending / protected / enforced) are overlaid by the panel from the
 * [AclUiState], not stored here, so this stays a pure projection of the hub snapshot.
 */
data class AclCell(
    val channelId: String,
    val agentId: String,
    /** `agentId ∈ channel.members`. A non-member cell is N/A — no toggles (CYP-19 §3). */
    val isMember: Boolean,
    /** Effective `canRead` via [AclMatrix] (membership AND every duplicate entry granting). */
    val canRead: Boolean,
    /** Effective `canWrite` via [AclMatrix]. R and W are independent (write-only is allowed). */
    val canWrite: Boolean,
    /** Multiple entries for this (channel, agent) — the strictest (deny-wins) value is shown. */
    val conflict: Boolean,
    /** PO who is a member here → removing a grant would break hub-and-spoke (advisory guardrail). */
    val poCritical: Boolean,
)

/**
 * Pure ACL-matrix reducers (CYP-48). Entry identity is `(channelId, agentId)`; [upsert] replaces by
 * key so a live [com.tneff.cyppieagents.model.AclEvent] and an optimistic toggle converge
 * idempotently (last-wins, CYP-19 §5.5). Effective grants come from the shared [AclMatrix] — this
 * never re-implements the decision. PO-lockout signalling is **advisory**; the server (CYP-49) enforces.
 */
object AclReducer {

    /** Inserts or replaces the entry for `(channelId, agentId)`. Idempotent by key. */
    fun upsert(entries: List<AclEntry>, entry: AclEntry): List<AclEntry> =
        entries.filterNot { it.channelId == entry.channelId && it.agentId == entry.agentId } + entry

    /** Drops any entry for `(channelId, agentId)` — reverts an optimistic insert that had no prior. */
    fun remove(entries: List<AclEntry>, channelId: String, agentId: String): List<AclEntry> =
        entries.filterNot { it.channelId == channelId && it.agentId == agentId }

    /** The current entry for `(channelId, agentId)`, or null. */
    fun entryFor(entries: List<AclEntry>, channelId: String, agentId: String): AclEntry? =
        entries.lastOrNull { it.channelId == channelId && it.agentId == agentId }

    fun statusOf(event: AclLiveEvent): ConnectionStatus? = when (event) {
        is AclLiveEvent.Connected -> ConnectionStatus.LIVE
        is AclLiveEvent.Disconnected -> ConnectionStatus.DISCONNECTED
        else -> null
    }

    /**
     * Advisory PO-lockout test (NOT enforcement — CYP-49): true when flipping a PO member's grant to
     * `false` on a **HUB** channel would strip a read/reply right that carries hub-and-spoke. Scoped to
     * `ChannelKind.HUB` to mirror the server guard (backend bef2cbb: only PO read/write on HUB-spokes is
     * blocked; worker toggles are untouched). The toggle still proceeds; the server returns 409 if it
     * really would lock out.
     */
    fun wouldLockoutPo(channelId: String, agent: Agent, newValue: Boolean, channels: List<Channel>): Boolean {
        if (agent.role != Role.PO || newValue) return false
        return channels.any { it.id == channelId && it.kind == ChannelKind.HUB && agent.id in it.members }
    }

    /** Builds the channel→cells grid for rendering, using the authoritative [AclMatrix] decision. */
    fun cells(channels: List<Channel>, agents: List<Agent>, entries: List<AclEntry>): Map<String, List<AclCell>> {
        val matrix = AclMatrix(channels, entries)
        val countByKey: Map<Pair<String, String>, Int> =
            entries.groupingBy { it.channelId to it.agentId }.eachCount()
        return channels.associate { channel ->
            channel.id to agents.map { agent ->
                val member = matrix.isMember(channel.id, agent.id)
                AclCell(
                    channelId = channel.id,
                    agentId = agent.id,
                    isMember = member,
                    canRead = matrix.canRead(channel.id, agent.id),
                    canWrite = matrix.canWrite(channel.id, agent.id),
                    conflict = (countByKey[channel.id to agent.id] ?: 0) > 1,
                    poCritical = agent.role == Role.PO && member && channel.kind == ChannelKind.HUB,
                )
            }
        }
    }

    /**
     * CYP-189 — cells for **human** subjects (roster `identityId` in the `agentId` slot), grantable on EVERY
     * channel. Unlike [cells] (agents), this is **membership-independent**: [AclCell.isMember] is always true
     * (no `NON_MEMBER` "—" suppression) because CYP-188 gates a human's send on `canWrite` at the chokepoint,
     * NOT on `Channel.members` (§3.1 — required by feature). Effective grants come straight from the entries
     * with the SAME **deny-wins** rule as [AclMatrix] (every matching entry must grant), but WITHOUT the
     * membership gate. Humans are never PO → [AclCell.poCritical] is always false (no lockout guardrail, §7.2).
     */
    fun humanCells(channels: List<Channel>, members: List<com.tneff.cyppieagents.model.WorkspaceMember>, entries: List<AclEntry>): Map<String, List<AclCell>> {
        val byKey: Map<Pair<String, String>, List<AclEntry>> = entries.groupBy { it.channelId to it.agentId }
        return channels.associate { channel ->
            channel.id to members.map { member ->
                val matching = byKey[channel.id to member.identityId].orEmpty()
                AclCell(
                    channelId = channel.id,
                    agentId = member.identityId,
                    isMember = true, // humans grantable on ALL channels — no membership suppression (§3.1)
                    canRead = matching.isNotEmpty() && matching.all { it.canRead }, // deny-wins, membership-independent
                    canWrite = matching.isNotEmpty() && matching.all { it.canWrite },
                    conflict = matching.size > 1,
                    poCritical = false, // a human is never PO — no hub-and-spoke lockout guardrail (§7.2)
                )
            }
        }
    }

    /**
     * The canonical hub-and-spoke target (CYP-19 §7 = `HubState.hubAndSpoke()`): every member of every
     * channel gets `canRead=true, canWrite=true`. Used for the preset's preview-diff and the N PUTs.
     */
    fun hubAndSpokePreset(channels: List<Channel>): List<AclEntry> =
        channels.flatMap { ch -> ch.members.map { AclEntry(ch.id, it, canRead = true, canWrite = true) } }

    /** Entries from [hubAndSpokePreset] that differ from the current effective state (the preview diff). */
    fun presetDiff(channels: List<Channel>, entries: List<AclEntry>): List<AclEntry> {
        val matrix = AclMatrix(channels, entries)
        return hubAndSpokePreset(channels).filterNot { matrix.canRead(it.channelId, it.agentId) && matrix.canWrite(it.channelId, it.agentId) }
    }

    fun cellKey(channelId: String, agentId: String): String = "$channelId|$agentId"
}
