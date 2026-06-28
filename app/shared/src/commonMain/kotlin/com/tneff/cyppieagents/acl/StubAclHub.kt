package com.tneff.cyppieagents.acl

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart

/**
 * In-memory ACL hub for ungated dev + tests (CYP-48): implements BOTH [AclApi] and [AclLiveSource]
 * over one mutable snapshot, so a `setAcl` PUT echoes an [AclLiveEvent.EntryChanged] exactly like the
 * real hub's `/ws/comm` broadcast. That lets the **enforced-after-AclEvent** path (CYP-19 §5.3) run
 * end-to-end without a server. Replaced by [AclRepository] + [AclWsClient] in prod.
 */
class StubAclHub(
    private val seedChannels: List<Channel> = DEFAULT_CHANNELS,
    private val seedAgents: List<Agent> = DEFAULT_AGENTS,
    seedEntries: List<AclEntry> = AclReducer.hubAndSpokePreset(DEFAULT_CHANNELS),
    /**
     * When true, `setAcl` mirrors the CYP-49 server guard: a PO-decoupling PUT on a HUB spoke is
     * rejected with `409 po_lockout_protected` and nothing persists — so the Maestro device-verify can
     * exercise the real `acl_po_protected` revert path. Default off keeps the pure tests deterministic.
     */
    private val rejectPoLockout: Boolean = false,
) : AclApi, AclLiveSource {

    private val entries = seedEntries.toMutableList()
    private val broadcasts = MutableSharedFlow<AclLiveEvent>(extraBufferCapacity = 64)

    override suspend fun channels(): List<Channel> = seedChannels

    override suspend fun agents(): List<Agent> = seedAgents

    override suspend fun acl(channelId: String?, agentId: String?): List<AclEntry> =
        entries.filter { (channelId == null || it.channelId == channelId) && (agentId == null || it.agentId == agentId) }

    override suspend fun setAcl(entry: AclEntry): AclEntry {
        if (rejectPoLockout && wouldLockOutPo(entry)) {
            throw AclHttpException(409, """{"error":{"code":"po_lockout_protected","message":"PO lockout protected"}}""")
        }
        entries.removeAll { it.channelId == entry.channelId && it.agentId == entry.agentId }
        entries.add(entry)
        broadcasts.tryEmit(AclLiveEvent.EntryChanged(entry)) // hub echo → the cell becomes enforced
        return entry
    }

    /** Mirrors the server guard (bef2cbb): a PO losing read or write on a HUB-spoke they belong to. */
    private fun wouldLockOutPo(entry: AclEntry): Boolean {
        if (entry.canRead && entry.canWrite) return false
        val agent = seedAgents.firstOrNull { it.id == entry.agentId } ?: return false
        val channel = seedChannels.firstOrNull { it.id == entry.channelId } ?: return false
        return agent.role == Role.PO && channel.kind == ChannelKind.HUB && entry.agentId in channel.members
    }

    override fun events(): Flow<AclLiveEvent> = broadcasts.onStart { emit(AclLiveEvent.Connected) }

    companion object {
        val DEFAULT_CHANNELS = listOf(
            Channel("po-frontend", "PO ⇄ Frontend", ChannelKind.HUB, listOf("po", "frontend", "operator")),
            Channel("po-backend", "PO ⇄ Backend", ChannelKind.HUB, listOf("po", "backend", "operator")),
        )
        val DEFAULT_AGENTS = listOf(
            Agent("po", "Product Owner", Role.PO, "po"),
            Agent("frontend", "Frontend", Role.WORKER, "frontend"),
            Agent("backend", "Backend", Role.WORKER, "backend"),
            Agent("operator", "Operator", Role.WORKER, "operator"),
        )
    }
}
