package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.ChannelReadState
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.ReadStateEvent
import com.tneff.cyppieagents.auth.ParticipantPrincipal
import com.tneff.cyppieagents.routing.ConflictException
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * CYP-705 — a [ReadStateEvent] tagged with its target [subject] for **self-only** `/ws/comm` routing. Server
 * -internal and never serialized: the `commSocket` forwards only the untagged [event] to the connection whose
 * participant == [subject], so the subject (an agentId / identityId) never reaches any client (content-free).
 */
data class TargetedReadState(val subject: String, val event: ReadStateEvent)

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
    // CYP-705: per-(principal, channel) read cursor. Default in-memory so dev/test installs are unchanged;
    // prod boot passes a durable SqliteReadCursorStore. Absence of a cursor ⇒ UNKNOWN (never a false 0).
    private val readCursors: ReadCursorStore = InMemoryReadCursorStore(),
) {
    // Live event stream for /ws/comm subscribers; filtered per participant at the WS boundary.
    private val _events = MutableSharedFlow<CommWsServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<CommWsServerEvent> = _events.asSharedFlow()

    // CYP-705: per-(viewer,channel) read-state deltas, tagged with their target subject for SELF-ONLY routing
    // at the /ws/comm boundary — the subject never reaches the wire (the client gets only the untagged event).
    private val _readState = MutableSharedFlow<TargetedReadState>(extraBufferCapacity = 256)
    val readStateEvents: SharedFlow<TargetedReadState> = _readState.asSharedFlow()

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
        _events.tryEmit(MessageEvent(deliveredOf(stored))) // CYP-744: live /ws/comm push carries the DeliveredMessage wrapper (spans) — same envelope as REST get/post (filtered per participant)
        // CYP-705: a new message moves every OTHER reader's unread for this channel — recompute + push each
        // (self-only routed). Emitted AFTER MessageEvent so a client never renders a count lagging the message.
        emitReadStateFor(stored.channelId, exclude = senderId)
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

    /**
     * CYP-705 — the calling [subject]'s read-state: one [ChannelReadState] per channel they `canRead` **and**
     * have a cursor for. A channel with no cursor is OMITTED ⇒ the caller reads absence as UNKNOWN (never a
     * fabricated 0). Server-computed [ChannelReadState.unreadCount]; self-only (no other principal's state).
     */
    fun readState(subject: String): List<ChannelReadState> {
        val pid = state.activeProjectId
        return state.channels.mapNotNull { ch ->
            if (!state.acl.canRead(ch.id, subject)) return@mapNotNull null
            val cursor = readCursors.lastReadSeq(pid, subject, ch.id) ?: return@mapNotNull null // no cursor ⇒ UNKNOWN ⇒ omit
            readStateOf(subject, ch.id, cursor)
        }
    }

    /**
     * CYP-705 — advance [subject]'s cursor in [channelId] to `max(existing, upToSeq)` (monotonic) and return the
     * updated read-state; emits a **self-only** [ReadStateEvent] (the non-optimistic echo the client waits for).
     * Throws 403 if [subject] may not read the channel (read-tier; you mark your OWN read-state, not a write).
     */
    fun markRead(subject: String, channelId: String, upToSeq: Long): ChannelReadState {
        if (!state.acl.canRead(channelId, subject)) {
            throw ForbiddenException("agent '$subject' has no read access to channel '$channelId'")
        }
        val pid = state.activeProjectId
        readCursors.markRead(pid, subject, channelId, upToSeq)
        val cursor = readCursors.lastReadSeq(pid, subject, channelId) ?: upToSeq
        val rs = readStateOf(subject, channelId, cursor)
        _readState.tryEmit(
            TargetedReadState(subject, ReadStateEvent(channelId, rs.lastReadSeq, rs.unreadCount, rs.hasUnreadMention)),
        )
        return rs
    }

    /**
     * CYP-705 + CYP-745 — the calling [subject]'s server-computed read-state for [channelId] past [cursor].
     *
     * **ONE filter pass, deliberately.** `unreadCount` and `hasUnreadMention` are both derived from the SAME
     * materialized `unread` set — project-scoped + `canRead` (via
     * [com.tneff.cyppieagents.model.AclMatrix.visibleMessages]), `seq > cursor`, own sends excluded. That makes
     * the cross-field invariant **structural, not coincidental**: `hasUnreadMention == true` with
     * `unreadCount == 0` is unreachable, because the flag can only be raised by an element of the very set
     * whose size is the count. Two independent traversals that merely happen to agree would be the drift bug
     * this shape forecloses — hence the single source. (Tooth: `Cyp745UnreadMentionTest`.)
     *
     * CYP-745 recognition reuses the ONE [MentionResolver] pass that also produces the Display overlay spans
     * (roster = the channel's members), so the Notify signal and the highlight can never disagree.
     */
    private fun readStateOf(subject: String, channelId: String, cursor: Long): ChannelReadState {
        val unread = state.acl.visibleMessages(subject, store.byChannel(channelId))
            .filter { it.seq > cursor && it.from != subject }
        val roster = membersOf(channelId)
        val mentionsMe = unread.any { MentionResolver.mentionsYou(subject, MentionResolver.resolve(it.body, roster)) }
        return ChannelReadState(channelId, cursor, unread.size, mentionsMe)
    }

    /** CYP-705 — push a fresh [ReadStateEvent] to every reader of [channelId] except [exclude] (self-only
     *  routed). A reader with no cursor stays UNKNOWN (omitted — a new message can't make UNKNOWN knowable). */
    private fun emitReadStateFor(channelId: String, exclude: String) {
        val pid = state.activeProjectId
        val ch = state.channels.firstOrNull { it.id == channelId } ?: return
        for (member in ch.members) {
            if (member == exclude) continue
            if (!state.acl.canRead(channelId, member)) continue
            val cursor = readCursors.lastReadSeq(pid, member, channelId) ?: continue // UNKNOWN stays UNKNOWN
            val rs = readStateOf(member, channelId, cursor)
            _readState.tryEmit(
                TargetedReadState(member, ReadStateEvent(channelId, rs.lastReadSeq, rs.unreadCount, rs.hasUnreadMention)),
            )
        }
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

    /**
     * CYP-744 — wrap [m] for a FRONTEND transport (/ws/comm + REST) with its server-resolved `@agent` mention spans.
     * The spans are viewer-INDEPENDENT Display spans (body-derived, roster-resolved) → resolved ONCE per message, not
     * per viewer. The stored [Message] is passed through WHOLE ([Message.seq]/meta/projectId/body intact — CYP-705's
     * unread line + cursor ride `seq`). The `/ws/hub` `WireMessage` path NEVER calls this, so the spans never reach the
     * BYOA agent wire (the §9-frame-guard). Single source for the WS echo, REST get, AND the REST post return — one
     * envelope, so the client's `messagesByChannel` slot never holds two shapes.
     */
    fun deliveredOf(m: Message): DeliveredMessage =
        DeliveredMessage(m, MentionResolver.resolve(m.body, membersOf(m.channelId)))

    private fun membersOf(channelId: String): List<String> =
        state.channels.firstOrNull { it.id == channelId }?.members ?: emptyList()

    /** CYP-744 — the FRONTEND message history: [channelMessages] wrapped as [DeliveredMessage] with resolved spans.
     *  REST `GET /api/channels/{id}/messages` serves THIS; `/ws/hub` keeps serving bare [channelMessages] (§9). */
    fun deliveredMessages(readerId: String, channelId: String, since: Long? = null): List<DeliveredMessage> =
        channelMessages(readerId, channelId, since).map(::deliveredOf)

    /**
     * CYP-870 (OS-E) — the reply-tree rooted at [rootId] in [channelId]: the root message + its transitive
     * `meta.inReplyTo`-descendants, ACL-`canRead`-filtered for [readerId] and ordered by `seq`. This READS the
     * existing thread structure — `inReplyTo` is a query label, never a routing authority; the method never
     * writes or routes. Empty when [rootId] is not visible to [readerId]. Cycles are guarded (a message collected
     * once is not revisited), so a malformed `inReplyTo` cycle cannot loop.
     */
    fun thread(readerId: String, channelId: String, rootId: String): List<DeliveredMessage> {
        val visible = channelMessages(readerId, channelId) // ACL-canRead-filtered channel history (bare Message)
        val byId = visible.associateBy { it.id }
        if (rootId !in byId) return emptyList()
        val childrenOf = visible.filter { it.meta?.inReplyTo != null }.groupBy { it.meta!!.inReplyTo!! }
        val collected = LinkedHashMap<String, Message>()
        val stack = ArrayDeque<String>().apply { add(rootId) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (id in collected) continue
            val msg = byId[id] ?: continue
            collected[id] = msg
            childrenOf[id]?.forEach { if (it.id !in collected) stack.add(it.id) }
        }
        return collected.values.sortedBy { it.seq }.map(::deliveredOf)
    }

    /**
     * CYP-870 (OS-E) — the [readerId]-visible messages of [channelId] filtered to a [MessageKind] (the Task/Status
     * surface: query a spoke's TASKs, or STATUS deltas with [since]). ACL-`canRead`-filtered. **`meta.kind` is a
     * POSTER LABEL rendered as-is, NOT a server-verified claim** (CYP-870 (A) honesty rule) — routing/authority is
     * NOT derived here; the write authority stayed the canWrite chokepoint at post time. A pure read/query.
     */
    fun messagesOfKind(readerId: String, channelId: String, kind: MessageKind, since: Long? = null): List<DeliveredMessage> =
        deliveredMessages(readerId, channelId, since).filter { it.message.meta?.kind == kind }

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

    /**
     * CYP-779 — the agent ids [readerId] may currently SEND to (for GET /api/agents/writable, the composer-
     * enable seam at agent granularity). Sending to agent `X` means posting into its hub-and-spoke channel
     * `po-<X>` ([com.tneff.cyppieagents.comm.HubState.spokeChannelFor], the SAME resolution [MediationRouter]
     * and the human send path use), which the chokepoint gates with `canWrite`. So writability-of-agent is
     * exactly writability-of-its-spoke — this DERIVES from [writableChannels] (already single-sourced on the
     * send-enforcing `AclMatrix.canWrite` AND the CYP-297 participant exclusion) rather than re-deriving the
     * ACL, so the two answers cannot drift and a participant token yields the empty set for free.
     *
     * An agent with no spoke the caller can write (a PO — the hub has no `po-po` — or a worker whose spoke the
     * caller lacks `canWrite` on) is excluded, fail-closed: you cannot compose a message to an agent you cannot
     * write. The server-403 at the send chokepoint stays the authority; this only drives the composer's enable.
     */
    fun writableAgents(readerId: String): List<String> {
        val writableSpokes = writableChannels(readerId).toSet()
        return state.agents.map { it.id }.filter { agentId -> state.spokeChannelFor(agentId) in writableSpokes }
    }
}
