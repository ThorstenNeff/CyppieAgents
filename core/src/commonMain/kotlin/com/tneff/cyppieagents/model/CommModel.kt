package com.tneff.cyppieagents.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical comm-hub domain contract (Spec 02 §5). One definition, compiled into both
 * `:server` and `:app:shared` via `:core`, so client and server share the exact shape.
 *
 * Security note: secrets never live in shared DTOs. An agent's static bearer token is held
 * server-side only (token → agentId registry); it is NOT a field of [Agent]. The wire view
 * of an agent is [Agent] itself — there is nothing to strip.
 */

enum class Role {
    @SerialName("PO") PO,
    @SerialName("WORKER") WORKER,
}

/** A participant in the hub. Hub role drives the default ACL (PO = hub of the spokes). */
@Serializable
data class Agent(
    val id: String,
    val name: String,
    val role: Role,
    /** worktree sub-folder name; informational on the wire, used server-side for spawns. */
    val worktree: String,
)

enum class ChannelKind {
    @SerialName("DIRECT") DIRECT,
    @SerialName("GROUP") GROUP,
    @SerialName("HUB") HUB,
}

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val members: List<String>,
)

/**
 * The configurable heart of the system (Spec 02 §5.3): exactly one entry per (channel, agent).
 * Hub-and-spoke is just a particular ACL assignment, not special-cased logic.
 */
@Serializable
data class AclEntry(
    val channelId: String,
    val agentId: String,
    val canRead: Boolean,
    val canWrite: Boolean,
)

enum class MessageKind {
    @SerialName("TASK") TASK,
    @SerialName("STATUS") STATUS,
    @SerialName("NOTE") NOTE,
}

@Serializable
data class MessageMeta(
    val inReplyTo: String? = null,
    val kind: MessageKind? = null,
)

@Serializable
data class Message(
    val id: String,
    val channelId: String,
    val from: String,
    val body: String,
    /** epoch millis */
    val ts: Long,
    val meta: MessageMeta? = null,
)

// ----- REST request/response wire types -----

/**
 * Body for POST /api/channels/{id}/messages. Deliberately carries NO `from` and NO channel
 * override: the sender is resolved from the bearer identity and the channel from the path,
 * never from client/agent-supplied content (Reviewer Gate #1).
 */
@Serializable
data class SendMessageRequest(
    val body: String,
    val meta: MessageMeta? = null,
)

@Serializable
data class ApiError(val code: String, val message: String)

/** Uniform error envelope (Spec 02 §7): `{ "error": { code, message } }`. */
@Serializable
data class ApiErrorBody(val error: ApiError)
