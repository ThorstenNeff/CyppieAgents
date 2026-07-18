package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.auth.ParticipantPrincipal
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The comm hub: the single place that enforces ACL on write and read, using the pure
 * [com.tneff.cyppieagents.model.AclMatrix] decision from [HubState] (no duplicated logic).
 *
 * Security gates (Reviewer):
 *  - #2 `canWrite` is checked **before** the store write, fail-closed, with an audit record.
 *  - #1 the channel and sender are passed in by the caller from the *authenticated identity* /
 *    URL path — never parsed from message content. [postAsAgent] never inspects the body for routing.
 *  - #3 the body is masked on the way in, so no secret is ever persisted or served.
 */
class Hub(
    val state: HubState,
    private val store: MessageStore,
    private val audit: Audit = Audit(),
    private val clock: Clock = Clock.SYSTEM,
    private val ids: IdGenerator = IdGenerator.UUIDS,
) {
    // Live event stream for /ws/comm subscribers; filtered per participant at the WS boundary.
    private val _events = MutableSharedFlow<CommWsServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<CommWsServerEvent> = _events.asSharedFlow()

    /**
     * CYP-132: invoked with the persisted [Message] AFTER every successful post — the single funnel the
     * [com.tneff.cyppieagents.mediation.MessageDeliverer] hooks for durable inbound delivery. Set once at
     * boot; default no-op so dev/test installs (and any non-boot use) are unchanged.
     */
    var onPosted: (Message) -> Unit = {}

    /**
     * CYP-698: invoked with the persisted [Message] AFTER every successful post — the SINGLE provenance
     * chokepoint. Wired once at boot to emit a `comm.sent` event-log entry (metadata only — from/channel/
     * kind, NEVER the body; identity is the server-stamped [Message.from], never a frame/body field). Because
     * it fires inside [postAsAgent], ALL callers get provenance automatically — MediationRouter, the remote
     * `/ws/hub` WireSend, the human CommRoutes, McpConnector, and any future 5th path — not per-caller.
     * Default no-op so dev/test installs (and any non-boot use) are unchanged.
     */
    var onSent: (Message) -> Unit = {}

    /** Post [body] from [senderId] into [channelId]. Throws 403 if the sender may not write. */
    fun postAsAgent(senderId: String, channelId: String, body: String, meta: MessageMeta? = null): Message {
        // Gate #2: fail-closed BEFORE any write.
        if (!state.acl.canWrite(channelId, senderId)) {
            audit.denied(senderId, channelId, "canWrite=false")
            throw ForbiddenException("agent '$senderId' has no write access to channel '$channelId'")
        }
        val maskedBody = SecretMasker.mask(body) // Gate #3 egress
        val message = Message(
            id = ids.newId(),
            channelId = channelId,
            from = senderId,
            body = maskedBody,
            ts = clock.now(),
            meta = meta,
            // S12 / CYP-81: stamp the server's single-sourced active project, never a client value —
            // same stance as `from`/`channelId` (Gate #1). The post already passed canWrite on an
            // in-project channel, so this records the message's tenant for project-scoped reads.
            projectId = state.activeProjectId,
        )
        // CYP-705: the store assigns the authoritative `seq`; thread the STORED copy through every downstream
        // funnel so the live MessageEvent, delivery, provenance, and the POST response all carry the real seq.
        val stored = store.append(message)
        audit.posted(stored)
        _events.tryEmit(MessageEvent(stored)) // live push to /ws/comm (filtered per participant)
        onPosted(stored) // CYP-132: durable inbound delivery — AFTER persist (the single funnel)
        onSent(stored)   // CYP-698: provenance emit (comm.sent) — AFTER persist, at the single chokepoint
        return stored
    }

    /**
     * Operator-gated ACL change: delegates to [HubState.setAcl] (the single mutation point, which
     * also enforces the CYP-49 PO-lockout guardrail fail-closed), audits it, and pushes [AclEvent] +
     * a refreshed [ChannelsEvent] to live subscribers (CYP-18). A rejected change throws before any
     * mutation, so no event is emitted and nothing is persisted.
     */
    fun setAcl(entry: AclEntry, by: String): AclEntry {
        val saved = try {
            state.setAcl(entry)
        } catch (e: ConflictException) {
            audit.aclDenied(entry.channelId, entry.agentId, by, e.message)
            throw e
        }
        audit.aclChanged(saved.channelId, saved.agentId, saved.canRead, saved.canWrite, by)
        _events.tryEmit(AclEvent(saved))
        _events.tryEmit(ChannelsEvent(state.channels))
        return saved
    }

    /** Messages of one channel for [readerId]; throws 403 if the reader may not read it. */
    fun channelMessages(readerId: String, channelId: String, since: Long? = null): List<Message> {
        if (!state.acl.canRead(channelId, readerId)) {
            throw ForbiddenException("agent '$readerId' has no read access to channel '$channelId'")
        }
        // S12 / CYP-81: route through the matrix so the project gate (and a defense-in-depth re-check
        // of canRead) applies to the rows too — an out-of-project message that shared this channel id
        // is dropped fail-closed, not served.
        return state.acl.visibleMessages(readerId, store.byChannel(channelId, since))
    }

    /** Aggregated inbox across all channels [readerId] may read (Spec 02 §6.3, ACL-filtered). */
    fun inbox(readerId: String, since: Long? = null): List<Message> {
        val readable = state.acl.readableChannels(readerId).map { it.id }
        val candidate = store.acrossChannels(readable, since)
        // Defense in depth: re-filter through the same decision even though channels are pre-filtered.
        return state.acl.visibleMessages(readerId, candidate)
    }

    /** Channels [readerId] may read (for GET /api/channels). */
    fun readableChannels(readerId: String): List<Channel> = state.acl.readableChannels(readerId)

    /**
     * CYP-273 — the channel ids [readerId] may currently WRITE (for GET /api/channels/writable, the composer-
     * enable seam). Computed via the SAME [com.tneff.cyppieagents.model.AclMatrix.canWrite] the send chokepoint
     * ([postAsAgent], above) enforces, so the client's composer-enable prediction == the server's write authz
     * (single-source, no drift). A **subset of [readableChannels]**: content-free (only ids the caller already
     * sees), correct (you never compose in a channel you can't read), and it never discloses a write-only-but-
     * unreadable channel. The server-403 at the chokepoint stays the authority; this only drives the UI.
     */
    fun writableChannels(readerId: String): List<String> {
        // CYP-273×297 — a participant token NEVER writes (CYP-297 Layer 3: requireCommWriter rejects it with 403
        // regardless of canWrite). So it must NEVER appear in the writable set, even if its `participant:` principal
        // was granted canWrite — else the seam's parity (id ∈ writable ⟺ POST 201) breaks and the enable-then-403
        // UX returns for participants. Single-sourced on CYP-297's own reserved-namespace predicate.
        if (ParticipantPrincipal.isParticipant(readerId)) return emptyList()
        return state.acl.readableChannels(readerId).map { it.id }.filter { state.acl.canWrite(it, readerId) }
    }
}
