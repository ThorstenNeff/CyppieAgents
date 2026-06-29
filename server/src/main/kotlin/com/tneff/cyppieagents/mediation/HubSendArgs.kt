package com.tneff.cyppieagents.mediation

import com.tneff.cyppieagents.model.MessageKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A parsed, validated `hub_send` command. [kind] is optional (null → default message kind). */
data class HubSendCommand(val channel: String, val text: String, val kind: MessageKind? = null)

/**
 * CYP-131 — pure, **fail-closed** parse of a `hub_send` tool-call's [com.tneff.cyppieagents.model.ToolUseBlock.input].
 * Returns null when a required arg ([HubTools.ARG_CHANNEL] / [HubTools.ARG_TEXT]) is missing, blank, or not a
 * JSON string — the caller then posts **nothing** and does not guess. [HubTools.ARG_KIND] is optional and
 * silently ignored if absent/unrecognized. The agent identity is NEVER taken from the args (server-stamped).
 */
object HubSendArgs {
    fun parse(input: JsonObject): HubSendCommand? {
        val channel = input.stringArg(HubTools.ARG_CHANNEL) ?: return null
        val text = input.stringArg(HubTools.ARG_TEXT) ?: return null
        val kind = input.stringArg(HubTools.ARG_KIND)?.let { runCatching { MessageKind.valueOf(it) }.getOrNull() }
        return HubSendCommand(channel, text, kind)
    }

    /** A non-blank JSON **string** value for [key], or null (a number/object/bool/missing/blank → null). */
    private fun JsonObject.stringArg(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
