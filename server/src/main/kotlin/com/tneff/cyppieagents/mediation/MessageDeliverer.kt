package com.tneff.cyppieagents.mediation

import com.tneff.cyppieagents.comm.DeliveryLog
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.events.EventProjector
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * CYP-132 — the "ear" of the mediator (RB1 core fix). Delivers readable hub messages into a recipient
 * agent's connector session as inbound [UserTurn]s, **durably**: a recipient that is down / not-yet-
 * attached is not dropped — the message stays in the [MessageStore] and is replayed on (re)attach.
 *
 * Design (reviewer-approved + R1–R3):
 *  - **persist → route → inject:** the message is already persisted by `Hub.postAsAgent` (store.append)
 *    BEFORE [onPosted] is called, so a crash anywhere leaves it recoverable from the store.
 *  - **R1 project-scope:** reads go through [com.tneff.cyppieagents.model.AclMatrix.visibleMessages] —
 *    the SAME gate REST uses, which enforces project-scope (CYP-81) + canRead + CYP-93 shares. So a
 *    foreign-project message in a reused channel id is never injected, and a naive `projectId ==` check
 *    (which would wrongly drop authorized cross-project shares) is avoided. Single-sourced, no drift.
 *  - **R3 reload-safe dedup:** [DeliveryLog] dedups over message.id, not a positional ordinal — a store
 *    reset with a surviving log still delivers new messages.
 *  - **M1 at-least-once:** the cursor (delivered-id) advances only AFTER a successful `sendTurn`, so a
 *    crash in the inject→mark window re-injects on the next drain (at-least-once; idempotent over a
 *    normal reconnect). Never sold as exactly-once.
 *  - **M2 concurrency:** drains are serialized per agent (a per-agent [Mutex]); `sendTurn` itself
 *    single-flights all three injectors (operator `/ws/agent`, Warden actuator, this deliverer) through
 *    the session turn-queue, so concurrent callers can't interleave a turn.
 *
 * watch-as-inbound falls out for free: the coordinator is just another readable member, so a worker's
 * STATUS post is delivered to the PO's session by the same rule.
 */
class MessageDeliverer(
    private val state: () -> HubState,
    private val projectId: () -> String,
    private val sessions: ConnectorSessions,
    private val store: MessageStore,
    private val log: DeliveryLog,
    private val scope: CoroutineScope,
    private val recorder: EventRecorder? = null,
    private val projector: EventProjector? = null,
) {
    private val logger = LoggerFactory.getLogger("mediation.deliverer")
    private val drainMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(agentId: String) = drainMutexes.getOrPut(agentId) { Mutex() }

    /**
     * LIVE trigger — called from `Hub.postAsAgent` AFTER the message is persisted (the single funnel).
     * Wakes the per-recipient drain for each readable, currently-attached member (≠ sender). A member
     * that is down is skipped here; its message stays persisted and is replayed by [onSessionAttached].
     */
    fun onPosted(message: Message) {
        val channel = state().channels.firstOrNull { it.id == message.channelId } ?: return
        for (member in channel.members) {
            if (member == message.from) continue
            if (sessions.session(member) == null) continue // down → replay on attach, not dropped
            launchDrain(member)
        }
    }

    /** ATTACH trigger — a (re)attached session replays everything not yet delivered to it (R3/AC3). */
    fun onSessionAttached(agentId: String) {
        launchDrain(agentId)
    }

    /** A drain failure must never be silently lost (it would look like a delivery gap) — log it. */
    private fun launchDrain(agentId: String) {
        scope.launch {
            runCatching { drain(agentId) }
                .onFailure { logger.error("delivery drain failed for agent={}", agentId, it) }
        }
    }

    /**
     * Reconcile [agentId]'s readable channels against the store and inject anything undelivered, in
     * append order, marking each only after a successful inject. Serialized per agent so a concurrent
     * [onPosted] + [onSessionAttached] cannot double-deliver.
     */
    private suspend fun drain(agentId: String) = mutexFor(agentId).withLock {
        val session = sessions.session(agentId) ?: return@withLock // not attached → leave pending (recoverable)
        val pid = projectId()
        val st = state()
        for (channel in st.channels) {
            if (agentId !in channel.members) continue
            // visibleMessages = project-scope (CYP-81/93) + canRead, single-sourced through the AclMatrix
            // (R1: no cross-project inject). store.byChannel alone is project-agnostic — must NOT be used raw.
            for (m in st.acl.visibleMessages(agentId, store.byChannel(channel.id))) {
                if (m.from == agentId) continue                      // never echo own send
                if (log.isDelivered(pid, agentId, m.id)) continue    // R3: dedup over message.id
                session.sendTurn(inboundTurn(m))                     // AT-LEAST-ONCE (M1)
                log.markDelivered(pid, agentId, m.id)                // after success ⇒ idempotent over reconnect
                if (recorder != null && projector != null) {
                    recorder.record(projector.commReceived(agentId, m.id, channel.id)) // metadata-only (m2)
                }
            }
        }
    }

    /**
     * Inbound framing: the recipient agent sees the hub message as a user message (its "ear").
     * Deterministic so tests can assert on the body; the body was already secret-masked at post time.
     */
    private fun inboundTurn(m: Message): UserTurn = UserTurn("[hub:${m.channelId}] ${m.from}: ${m.body}")
}
