package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.comm.SecretMasker
import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.ContentBlock
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.TextBlock
import com.tneff.cyppieagents.model.ThinkingBlock
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.serialization.json.JsonObject

/**
 * Applies [SecretMasker] to every string-bearing part of a [StreamJsonEvent] BEFORE it leaves the
 * connector (Reviewer Gate #3: masking on every egress — assistant text, thinking, `tool_use.input`
 * command strings, and `tool_result.content` command output, which can carry a leaked key).
 */
object EventMasking {

    fun mask(event: StreamJsonEvent): StreamJsonEvent = when (event) {
        is AssistantEvent -> event.copy(message = maskMessage(event.message))
        is UserEvent -> event.copy(message = maskMessage(event.message))
        is ResultEvent -> event.copy(result = event.result?.let { SecretMasker.mask(it) })
        is SystemEvent -> event
        is RateLimitEvent -> event
    }

    private fun maskMessage(message: AgentMessage): AgentMessage =
        message.copy(content = message.content.map { maskBlock(it) })

    private fun maskBlock(block: ContentBlock): ContentBlock = when (block) {
        is TextBlock -> block.copy(text = SecretMasker.mask(block.text))
        is ThinkingBlock -> block.copy(thinking = block.thinking?.let { SecretMasker.mask(it) })
        is ToolUseBlock -> block.copy(input = SecretMasker.maskJson(block.input) as JsonObject)
        is ToolResultBlock -> block.copy(content = block.content?.let { SecretMasker.maskJson(it) })
    }
}
