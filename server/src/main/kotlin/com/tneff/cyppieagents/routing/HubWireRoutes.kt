package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.model.CapabilityCeiling
import com.tneff.cyppieagents.model.WireDeliver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tneff.cyppieagents.model.ConnectorTrust
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.SUPPORTED_WIRE_VERSIONS
import com.tneff.cyppieagents.model.WireAck
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireError
import com.tneff.cyppieagents.model.WireErrorCode
import com.tneff.cyppieagents.model.WireFrame
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireMessage
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

/**
 * E2.2 / CYP-138 — **Hub-Wire-Protocol v1** over `GET /ws/hub` (Spec §8 transport reuse, D3 = Ktor WS).
 * The versioned external boundary for remote / BYOA connectors: connect+auth → `WireHello` (capability
 * handshake, **clamped REMOTE**) → `WireSend` (the abstract send, Doc 11 §6) → `WireSubscribe`.
 *
 * Security stance — every guard is a chokepoint reuse, not a new path:
 *  - **Auth-first (A1/A2):** the bearer token resolves the bound `agentId` BEFORE any frame is read; an
 *    operator/unknown/absent token → close `VIOLATED_POLICY` (only agents emit; identity is the token,
 *    never a frame field). `from`/`projectId` are server-stamped downstream by [Hub.postAsAgent].
 *  - **Version gate (R3):** every envelope carries a supported `v` or is rejected fail-closed.
 *  - **FO#1 clamp (R2):** the handshake's self-declared caps are clamped to the **REMOTE** ceiling —
 *    the SINGLE caps-ingress for a wire agent; trust is **structurally REMOTE** (a literal, never frame-
 *    derived), so a lie degrades (DEGRADED), never escalates (ENABLED).
 *  - **Single write path:** `WireSend` routes ONLY through [Hub.postAsAgent] (no second `store.append` /
 *    `Message(...)`), size-capped at the edge ([MessageInput], A5). A `canWrite` denial → uniform
 *    `WireError(FORBIDDEN)` (no "no such channel" vs "forbidden" leak, M2).
 *  - **Read egress (R1):** `WireSubscribe` funnels [Hub.channelMessages] **verbatim** (project-scope +
 *    canRead + CYP-93 shares) — never raw `store.byChannel`.
 *  - **Fail-closed framing (R3):** a malformed envelope or an unknown/unexpected frame → `WireError` +
 *    close, never a silent drop or an unhandled throw. A `Send`/`Subscribe` before `WireHello`, or a
 *    second `WireHello`, is a defined `PROTOCOL` rejection (M1) — never a 2nd unclamped caps `set`.
 */
fun Route.hubWireRoutes(
    hub: Hub,
    registry: TokenRegistry,
    capabilityRegistry: CapabilityRegistry,
    providerRegistry: ProviderRegistry,
    rateLimiter: WireRateLimiter,
    connectorSessions: ConnectorSessions,
    // S5 / G4: the Event-Log sink + active project — a remote's WireEvent self-report is recorded here
    // (server-stamped source=remote), restoring event-log tool depth + the Warden stall-net for remote.
    eventRecorder: com.tneff.cyppieagents.events.EventRecorder,
    activeProjectId: () -> String,
    // CYP-173: slow-loris reap — max time an authenticated connection may stay BEFORE completing the
    // WireHello handshake. Generous for a real bridge (handshake is immediate); tests override it short.
    helloTimeoutMs: Long = 10_000,
    // CYP-198: the durable per-agent transcript feeder (LAST param — purely additive for existing positional
    // callers). A remote agent's window has no local stream-json session, so its self-reported WireEvents are
    // persisted here (masked/whitelisted at ingest) so the remote window survives reconnect too. Null = none.
    agentEvents: com.tneff.cyppieagents.agentevents.AgentEventRecorder? = null,
) {
    webSocket("/ws/hub") {
        // Auth FIRST, fail-closed, BEFORE any frame: only an agent token (→ its own agentId) may connect.
        // agentFor(operatorToken)==null and agentFor(unknown)==null → both rejected (only agents emit).
        val agentId = registry.agentFor(call.bearerToken() ?: call.request.queryParameters["token"])
        if (agentId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }
        // CYP-172: at-connect roster cross-check. `agentFor` is a bare token→agentId map (Auth.kt:32) with no
        // roster validation, so a token OUTLIVES its agent's membership: after a project switch (`state.rescope`,
        // HubState.kt:277) the outgoing project's agents are stashed while their tokens stay bound in the
        // registry, so a stale token still resolves to an agentId above. Require that agentId to be a LIVE
        // participant of the ACTIVE roster (`state.agent(id) != null`) — else refuse with the SAME "unauthorized"
        // reason as an unknown token. This is the sole admission gate for the stale/cross-project agent: cutting
        // the connection here (before any frame) also denies its cross-project event-log write (edge ③), and
        // closes the unrevocability gap (a stashed agent cannot be revoked via the normal route).
        //
        // DELIBERATE SEMANTIC (Topology-A, flagged not silent): `state.agents` is the ACTIVE project's roster,
        // and remote agents are excluded from the per-project store (`toStore = … && !spec.remote`,
        // AgentManagement.kt:178) → there is no per-project roster covering runtime remote agents. So this check
        // ALSO refuses the bridge of a legitimately INACTIVE project, not only a switched-away/deleted one.
        // Defensible while one hub serves one active project; REVISIT for multi-active-project topologies. It
        // couples with the (deferred) all-or-nothing remote-aware rehydration: a legit ACTIVE-project remote
        // agent must be rehydrated back INTO `state.agents` on reboot, else this gate would refuse it — today it
        // is already refused post-reboot because CYP-690's boot orphan-purge revokes the not-in-roster token, so
        // this adds no new post-reboot breakage; it closes the WITHIN-runtime switch hole the tooth pins.
        if (hub.state.agent(agentId) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }

        // RC1 (CYP-141): ONE shared lock guards EVERY send on this WS — the read-loop's reply() AND the
        // deliverer's async WireDeliver push (the wire-backed session). Two unsynchronized writers on one
        // socket interleave frames / hit ClosedSendChannelException.
        val sendLock = Mutex()
        suspend fun reply(frame: WireFrame) = sendLock.withLock {
            send(Frame.Text(CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, frame))))
        }
        var handshook = false
        // The flood-close counter is per-CONNECTION-local (NOT in the limiter). A fresh connection of a
        // throttled agent gets a fresh counter (no stale insta-close) — yet its sends are still throttled,
        // because the agent's SHARED bucket (in [rateLimiter]) is still empty. Tokens shared, counter local.
        var consecutiveRejects = 0
        // CYP-141: the wire-backed session, registered at handshake, removed (compare-and-remove) on close.
        var session: WireConnectorSession? = null
        // CYP-173: slow-loris reap. An authenticated connection that never sends WireHello leaves the
        // read-loop blocked on `incoming` forever (each such socket holds a coroutine + the connection —
        // resource exhaustion). This watchdog reaps it after [helloTimeoutMs] unless handshook; it is
        // cancelled on a successful Hello (below) and auto-cancelled when the session scope closes.
        val helloWatchdog = launch {
            delay(helloTimeoutMs)
            if (!handshook) {
                runCatching { reply(WireError(WireErrorCode.PROTOCOL, "handshake timeout")) }
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "handshake timeout"))
            }
        }
        try {
            for (frame in incoming) {
            if (frame !is Frame.Text) continue
            // R3: a malformed / non-decodable envelope (incl. an unknown frame `type`) → fail-closed.
            val env = runCatching { CommJson.decodeFromString<WireEnvelope>(frame.readText()) }.getOrNull()
            if (env == null) {
                reply(WireError(WireErrorCode.BAD_REQUEST, "malformed or unknown frame"))
                return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "bad request"))
            }
            // Version gate (R3): an unsupported version is rejected, never best-effort-parsed.
            if (env.v !in SUPPORTED_WIRE_VERSIONS) {
                reply(WireError(WireErrorCode.UNSUPPORTED_VERSION, "v=${env.v}"))
                return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "unsupported version"))
            }

            when (val f = env.frame) {
                is WireHello -> {
                    // M1: one handshake per connection. A second Hello is a defined rejection — NEVER a
                    // second (unclamped) caps `set`. (reject, not idempotent re-clamp.)
                    if (handshook) {
                        reply(WireError(WireErrorCode.PROTOCOL, "already handshook"))
                        return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "second hello"))
                    }
                    // R2 ⭐ FO#1: the ONLY caps-ingress for a wire agent. Trust is STRUCTURALLY REMOTE
                    // (literal — never read from the frame), so a LOCAL claim cannot widen the ceiling.
                    capabilityRegistry.set(
                        agentId,
                        CapabilityCeiling.clamp(f.capabilities, CapabilityCeiling.ceilingFor(ConnectorTrust.REMOTE)),
                    )
                    providerRegistry.set(agentId, f.provider)
                    handshook = true
                    helloWatchdog.cancel() // CYP-173: handshake completed in time — stand the reaper down
                    reply(WireAck("hello")) // Ack BEFORE register, so a replayed WireDeliver can't precede it
                    // CYP-141 ⭐: register the wire-backed session → fires deliverer.onSessionAttached →
                    // drains any pending inbound (at-least-once replay). RC1: sendDeliver pushes WireDeliver
                    // through the SAME sendLock as reply(). RC2: sendTurn propagates a closed-WS throw, so the
                    // deliverer's markDelivered (after-success) doesn't advance → re-delivered on reconnect.
                    val s = WireConnectorSession(
                        agentId,
                        sendDeliver = { reply(WireDeliver(it)) },
                        closeWs = { outgoing.close() },
                    )
                    session = s
                    // CYP-775: close-then-accept, never silent-last-wins. A still-live incumbent under this agentId
                    // (a legitimate reconnect, or a second "dead" bridge whose HUB_TOKEN is valid but whose claude is
                    // down) is cleanly closed + JOINED before we register — removeAndAwait → closeAndAwait → the wire
                    // session's outgoing.close(), a WS-close SIGNAL to the old holder. Mirrors LifecycleManager.start's
                    // removeAndAwait-before-doSpawn for the LOCAL path (the double-spawn path is already serialised by
                    // CYP-368). A raw register() here would drop the incumbent out of byAgent WITHOUT closing it — a
                    // silent mute-zombie (still connected, receives nothing). No-op when there is no incumbent.
                    connectorSessions.removeAndAwait(agentId)
                    connectorSessions.register(s)
                }

                is WireSend -> {
                    if (!handshook) {
                        reply(WireError(WireErrorCode.PROTOCOL, "hello required before send"))
                        return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "send before hello"))
                    }
                    // E2.5a / CYP-161: rate-limit FIRST (before size-cap + postAsAgent) so a flood is dropped
                    // before any work. Bucket is per-agentId (shared across this agent's connections, RL5).
                    if (!rateLimiter.tryAcquire(agentId)) {
                        reply(WireError(WireErrorCode.RATE_LIMITED, "rate limit exceeded — back off")) // transient
                        // RC4 flood backstop: sustained throttling on THIS connection → close fail-closed.
                        if (++consecutiveRejects >= rateLimiter.floodCloseAfter) {
                            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "flood"))
                        }
                        continue
                    }
                    consecutiveRejects = 0 // an accepted send resets THIS connection's flood counter
                    // A5: size-cap at the wire edge (CYP-143 reuse) — oversized/blank → nothing posted.
                    try {
                        MessageInput.requireValidBody(f.text)
                    } catch (e: ApiException) {
                        reply(WireError(WireErrorCode.TOO_LARGE, e.message))
                        continue
                    }
                    // SINGLE write path: identity = the bound agentId (never a frame field); canWrite +
                    // from/projectId stamp + mask all live in postAsAgent. canWrite denial → uniform 403.
                    try {
                        val posted = hub.postAsAgent(agentId, f.channel, f.text, f.kind?.let { MessageMeta(kind = it) })
                        reply(WireAck("sent ${posted.id}"))
                    } catch (e: ForbiddenException) {
                        reply(WireError(WireErrorCode.FORBIDDEN, "no write access to channel '${f.channel}'"))
                    }
                }

                is WireSubscribe -> {
                    if (!handshook) {
                        reply(WireError(WireErrorCode.PROTOCOL, "hello required before subscribe"))
                        return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "subscribe before hello"))
                    }
                    // R1 ⭐: funnel channelMessages VERBATIM (canRead + visibleMessages = project-scope +
                    // CYP-93 shares per channel). NEVER raw store.byChannel — that would leak a foreign
                    // project's message reusing this channel id to a remote subscriber.
                    for (ch in f.channels) {
                        val msgs = runCatching { hub.channelMessages(agentId, ch, f.since) }.getOrDefault(emptyList())
                        msgs.forEach { reply(WireMessage(it)) }
                    }
                    reply(WireAck("subscribed"))
                }

                is com.tneff.cyppieagents.model.WireEvent -> {
                    // G4-1: Hello-required — identity + the REMOTE caps clamp must be established first.
                    if (!handshook) {
                        reply(WireError(WireErrorCode.PROTOCOL, "hello required before event"))
                        return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "event before hello"))
                    }
                    // G4-2: rate-limit FIRST (the SAME per-agent bucket as WireSend); a sustained flood closes.
                    if (!rateLimiter.tryAcquire(agentId)) {
                        reply(WireError(WireErrorCode.RATE_LIMITED, "rate limit exceeded — back off"))
                        if (++consecutiveRejects >= rateLimiter.floodCloseAfter) {
                            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "flood"))
                        }
                        continue
                    }
                    consecutiveRejects = 0
                    // G4-3/G4-5: record the self-report attributed to the BOUND agentId (never a frame field),
                    // server-stamped source=remote, fields whitelist-dropped + size-capped ([WireEventIngest]).
                    // G4-4: NO capabilityRegistry write — caps stay Hello-only. Fire-and-forget telemetry, no ack.
                    eventRecorder.record(WireEventIngest.toDraft(agentId, activeProjectId(), f))
                    // CYP-198: also persist the remote signal into the durable agent-window transcript, so a
                    // REMOTE agent's window survives a reconnect. The remote wire carries NO rich stream-json
                    // (REMOTE clamp — assistant text stays in user infra); the rate-limit signal is the
                    // observable, already whitelisted at ingest. Tool signals live in the Event-Log metadata.
                    if (f.signal == com.tneff.cyppieagents.model.WireEventType.RATE_LIMIT && f.rateLimit != null) {
                        agentEvents?.record(
                            agentId, activeProjectId(),
                            com.tneff.cyppieagents.model.RateLimitEvent(
                                rateLimitInfo = kotlinx.serialization.json.buildJsonObject {
                                    f.rateLimit!!.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                                },
                            ),
                        )
                    }
                }

                // Server→client frames arriving as input (Ack/Message/Error) are unexpected → fail-closed.
                else -> {
                    reply(WireError(WireErrorCode.BAD_REQUEST, "unexpected frame"))
                    return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "unexpected frame"))
                }
            }
            }
        } finally {
            helloWatchdog.cancel() // CYP-173: never leave the reaper running past the connection's life
            // RC3: evict ONLY if still the current registered instance — a reconnect that already replaced
            // this session is left untouched (a blind remove would orphan the new connection).
            session?.let { connectorSessions.removeIfSame(it) }
        }
    }
}
