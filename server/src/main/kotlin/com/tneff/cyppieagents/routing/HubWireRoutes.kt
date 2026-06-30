package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.connector.CapabilityRegistry
import com.tneff.cyppieagents.connector.ProviderRegistry
import com.tneff.cyppieagents.model.CapabilityCeiling
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
import io.ktor.server.websocket.DefaultWebSocketServerSession
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
) {
    webSocket("/ws/hub") {
        // Auth FIRST, fail-closed, BEFORE any frame: only an agent token (→ its own agentId) may connect.
        // agentFor(operatorToken)==null and agentFor(unknown)==null → both rejected (only agents emit).
        val agentId = registry.agentFor(call.bearerToken() ?: call.request.queryParameters["token"])
        if (agentId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return@webSocket
        }

        var handshook = false
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
                    reply(WireAck("hello"))
                }

                is WireSend -> {
                    if (!handshook) {
                        reply(WireError(WireErrorCode.PROTOCOL, "hello required before send"))
                        return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "send before hello"))
                    }
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

                // Server→client frames arriving as input (Ack/Message/Error) are unexpected → fail-closed.
                else -> {
                    reply(WireError(WireErrorCode.BAD_REQUEST, "unexpected frame"))
                    return@webSocket close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "unexpected frame"))
                }
            }
        }
    }
}

/** Encode a server→client [WireFrame] in a v1 envelope and send it as one text frame. */
private suspend fun DefaultWebSocketServerSession.reply(frame: WireFrame) {
    send(Frame.Text(CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, frame))))
}
