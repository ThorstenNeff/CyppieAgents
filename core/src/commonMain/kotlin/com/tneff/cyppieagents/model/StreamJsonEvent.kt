package com.tneff.cyppieagents.model

import com.tneff.cyppieagents.CommJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * stream-json / NDJSON events emitted by the Claude CLI on stdout (server→client direction),
 * as observed in the CYP-5 spike (CLI 2.1.193) and **frozen jointly with Dev (CYP-9)**.
 *
 * Named [StreamJsonEvent] (not `AgentEvent`) to avoid colliding with the Dev UI model
 * `AgentEvent` in `:app:shared` — the mediator maps StreamJsonEvent → AgentEvent.
 *
 * Decoded through [CommJson] (classDiscriminator = "type", ignoreUnknownKeys = true); the
 * top-level `type` is the discriminator. Common envelope: `session_id`, `uuid`.
 */
@Serializable
sealed interface StreamJsonEvent {
    val sessionId: String?
    val uuid: String?
}

/** `{"type":"system","subtype":"init", ...}` — turn/session start; source of `session_id`. */
@Serializable
@SerialName("system")
data class SystemEvent(
    val subtype: String? = null,
    @SerialName("session_id") override val sessionId: String? = null,
    override val uuid: String? = null,
    val model: String? = null,
    val cwd: String? = null,
    /** The CLI emits the available tool NAMES as an array (verified on a real run, CYP-13). */
    val tools: List<String>? = null,
    val permissionMode: String? = null,
    @SerialName("apiKeySource") val apiKeySource: String? = null,
) : StreamJsonEvent {
    val toolCount: Int? get() = tools?.size
}

/** `{"type":"rate_limit_event", ...}` — observability/spend signal; not routed to the hub. */
@Serializable
@SerialName("rate_limit_event")
data class RateLimitEvent(
    @SerialName("rate_limit_info") val rateLimitInfo: JsonObject? = null,
    @SerialName("session_id") override val sessionId: String? = null,
    override val uuid: String? = null,
) : StreamJsonEvent

/** `{"type":"assistant","message":{...}}` — assistant turn output (thinking/text/tool_use). */
@Serializable
@SerialName("assistant")
data class AssistantEvent(
    val message: AgentMessage,
    @SerialName("session_id") override val sessionId: String? = null,
    override val uuid: String? = null,
    @SerialName("parent_tool_use_id") val parentToolUseId: String? = null,
) : StreamJsonEvent {
    /**
     * Stable per-assistant-message id (= `message.id`). Exposed in the assistant path so later
     * token-streaming (partial messages) can update a message in place without a DTO break.
     * MVP default: partial-messages OFF, so a turn arrives complete.
     */
    val messageId: String? get() = message.id
}

/** `{"type":"user","message":{...}}` — our injected/replayed input, or tool_result blocks. */
@Serializable
@SerialName("user")
data class UserEvent(
    val message: AgentMessage,
    @SerialName("session_id") override val sessionId: String? = null,
    override val uuid: String? = null,
) : StreamJsonEvent

/**
 * `{"type":"result","subtype":"success", ...}` — the **turn-end marker**. The mediation /
 * ACL write boundary lives here (Reviewer Gate #2/#6): only a non-error success turn is posted.
 */
@Serializable
@SerialName("result")
data class ResultEvent(
    val subtype: String? = null,
    @SerialName("is_error") val isError: Boolean = false,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("num_turns") val numTurns: Int? = null,
    @SerialName("total_cost_usd") val totalCostUsd: Double? = null,
    val usage: JsonObject? = null,
    val result: String? = null,
    @SerialName("session_id") override val sessionId: String? = null,
    override val uuid: String? = null,
) : StreamJsonEvent {
    /** True only for a fully successful turn — the sole case the mediator posts as success. */
    val isSuccess: Boolean get() = !isError && (subtype == null || subtype == "success")
}

/** The Anthropic-style message wrapper carried by assistant/user events. */
@Serializable
data class AgentMessage(
    /** Anthropic message id (`message.id`); the stable key for in-place streaming updates. */
    val id: String? = null,
    val role: String? = null,
    val content: List<ContentBlock> = emptyList(),
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

/** Content blocks inside [AgentMessage]; discriminated by their own `type`. */
@Serializable
sealed interface ContentBlock

@Serializable
@SerialName("text")
data class TextBlock(val text: String) : ContentBlock

@Serializable
@SerialName("thinking")
data class ThinkingBlock(val thinking: String? = null) : ContentBlock

@Serializable
@SerialName("tool_use")
data class ToolUseBlock(
    /** `toolu_…` — stably exposed; pairs with [ToolResultBlock.toolUseId] for RUNNING→OK/ERROR. */
    val id: String,
    val name: String,
    /** arbitrary tool args; e.g. Bash command strings — a masking egress point (Gate #3). */
    val input: JsonObject = JsonObject(emptyMap()),
) : ContentBlock

@Serializable
@SerialName("tool_result")
data class ToolResultBlock(
    /** `tool_use_id` — stably exposed; ties a result back to its [ToolUseBlock.id]. */
    @SerialName("tool_use_id") val toolUseId: String? = null,
    /** tool output (string or array) — also a masking egress point (Gate #3). */
    val content: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean = false,
) : ContentBlock

/**
 * Client→server turn (mediator → CLI stdin). Serializes to the exact verified stdin shape
 * (CYP-5): `{"type":"user","message":{"role":"user","content":[{"type":"text","text":…}]}}`,
 * one NDJSON line per turn. Built explicitly so the shape can't drift from the verified one.
 */
@Serializable
data class UserTurn(val text: String) {
    fun toNdjsonLine(): String {
        val obj: JsonObject = buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            }
        }
        return CommJson.encodeToString(JsonObject.serializer(), obj)
    }
}
