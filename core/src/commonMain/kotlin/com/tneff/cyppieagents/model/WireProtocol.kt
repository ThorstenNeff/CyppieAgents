package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * E2.2 / CYP-138 — **Hub-Wire-Protocol v1**: the versioned, external realization of the abstract
 * send/read operation (Doc 11 §6) for **remote / BYOA connectors** over the `/ws/hub` WebSocket. It is
 * the public boundary in front of the same in-process chokepoints the local paths use — every wire
 * `WireSend` funnels through `Hub.postAsAgent` (canWrite-403 / server-stamped `from`+`projectId` / mask /
 * size-cap), every `WireSubscribe` through `Hub.channelMessages` (project-scope + canRead + shares).
 *
 * Decoded through [com.tneff.cyppieagents.CommJson] (`classDiscriminator = "type"`, `ignoreUnknownKeys`).
 * Predecessor of E2.5/CYP-141 (Remote-Accept): the wire must **stand AND ceiling-clamp** before remote opens.
 */

/**
 * The single source of supported wire versions. Every [WireEnvelope] declares its `v`; an unsupported
 * version is rejected **fail-closed** (a `WireError(UNSUPPORTED_VERSION)` + close), never best-effort-parsed.
 */
val SUPPORTED_WIRE_VERSIONS: Set<Int> = setOf(1)

/** Every wire frame (both directions) travels wrapped in this versioned envelope. */
@Serializable
data class WireEnvelope(val v: Int, val frame: WireFrame)

@Serializable
sealed interface WireFrame

// ---------- client → server ----------

/**
 * The handshake (MUST be the first frame): the remote's **self-declared** capabilities + provider.
 * The server **clamps** the capabilities to the REMOTE ceiling before any right is honored (FO#1) — the
 * declaration is *data, not authority*. Carries no trust/agentId/projectId: identity is server-bound.
 */
@Serializable
@SerialName("hello")
data class WireHello(val capabilities: Capabilities, val provider: ProviderInfo) : WireFrame

/**
 * The abstract send operation (Doc 11 §6). Deliberately carries **no `from` and no `projectId`** — the
 * sender is the bearer-bound agent and the project is the server's active one, both server-stamped by
 * `postAsAgent` (A1/A2: a wire client cannot spoof identity or tenant).
 */
@Serializable
@SerialName("send")
data class WireSend(val channel: String, val text: String, val kind: MessageKind? = null) : WireFrame

/** Request the ACL-filtered readable history for [channels] (optional `since` cursor) as `WireMessage` frames. */
@Serializable
@SerialName("subscribe")
data class WireSubscribe(val channels: List<String>, val since: Long? = null) : WireFrame

/**
 * S5 / G4 — a remote agent's **self-report** of a structured signal the text-only relay drops, so its
 * `rateLimitSignal`/`toolGranularity` caps can honestly be **LIMITED** (S1/E2.8 verdict) — restoring the
 * Warden stall-net + event-log tool depth for remote. Carries ONLY a content-free signal, never raw tool
 * I/O. **The bridge is UNTRUSTED** (a malicious user can handcraft this frame), so the SERVER treats every
 * field as an opaque, whitelisted + size-capped display label (G4-5) and stamps provenance itself:
 *  - it NEVER touches the capability clamp (caps are `WireHello`-only, REMOTE-clamped; G4-4).
 *  - it carries **no `source` / `agentId` / `projectId`** — the server stamps `source=remote` (the wire
 *    provenance) and the bearer-bound agentId; a frame cannot claim `LOCAL` or spoof identity.
 */
@Serializable
@SerialName("event")
data class WireEvent(
    // named `signal` (not `type`) — `type` is the polymorphic [WireFrame] discriminator (CommJson), and a
    // property named `type` would collide with it at (de)serialization.
    val signal: WireEventType,
    /** RATE_LIMIT only: rate-limit fields. The server keeps ONLY whitelisted keys; values are size-capped. */
    val rateLimit: Map<String, String>? = null,
    /** TOOL_CALL/TOOL_RESULT only: the tool NAME (server size-capped); never the tool input/output. */
    val tool: String? = null,
) : WireFrame

@Serializable
enum class WireEventType {
    @SerialName("rate_limit") RATE_LIMIT,
    @SerialName("tool_call") TOOL_CALL,
    @SerialName("tool_result") TOOL_RESULT,
}

// ---------- server → client ----------

/** A handshake / send / subscribe was accepted. */
@Serializable
@SerialName("ack")
data class WireAck(val detail: String? = null) : WireFrame

/** One readable inbound message (already ACL-filtered + masked at the source). */
@Serializable
@SerialName("message")
data class WireMessage(val message: Message) : WireFrame

/**
 * E2.5 / CYP-141 — the deliverer's **inbound push** to a remote agent (hub→remote): the durable,
 * deduped, at-least-once task/status the [com.tneff.cyppieagents.mediation.MessageDeliverer] injects.
 * [text] is the formatted `inboundTurn` (`"[hub:<channel>] <from>: <body>"`), already ACL-filtered +
 * secret-masked at the source. The remote connector injects it into its local agent's session.
 */
@Serializable
@SerialName("deliver")
data class WireDeliver(val text: String) : WireFrame

/** A fail-closed error. [WireErrorCode] is uniform (a forbidden `Send` and an unknown channel share the
 *  SAME `FORBIDDEN` shape — no topology leak, M2). */
@Serializable
@SerialName("error")
data class WireError(val code: WireErrorCode, val detail: String? = null) : WireFrame

@Serializable
enum class WireErrorCode {
    @SerialName("unsupported_version") UNSUPPORTED_VERSION,
    @SerialName("forbidden") FORBIDDEN, // 403 — uniform for "no such channel" AND "forbidden channel"
    @SerialName("too_large") TOO_LARGE, // oversized/blank body (size-cap at the edge)
    @SerialName("bad_request") BAD_REQUEST, // malformed envelope / unknown or unexpected frame type
    @SerialName("protocol") PROTOCOL, // ordering violation: send/subscribe before hello, or a 2nd hello
    @SerialName("rate_limited") RATE_LIMITED, // E2.5a/CYP-161: token-bucket throttle — the transient "back off"
}
