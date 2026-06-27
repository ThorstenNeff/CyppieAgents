package com.tneff.cyppieagents.events

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
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Two-class needle-injection harness for the Event-Log metadata-only proof (CYP-44 §4). [inject]
 * adds BOTH needles to every **content-bearing** field of a real corpus `StreamJsonEvent` — never a
 * metadata field (tool *name*, ids) the projector legitimately records. The §4 test then runs the
 * needled corpus through the real `mask → project` chain and asserts absence in every egress.
 *
 * The two classes are the whole point (see [NeedleHarnessSelfTest]):
 *  - [SECRET_NEEDLE] is secret-shaped (`sk-ant-…`) → `SecretMasker` redacts it (the masking belt).
 *  - [PLAIN_NEEDLE] is innocuous content the masker does NOT touch → only the projector's *structural*
 *    metadata-only exclusion can keep it out of `Event.detail`. If the plain needle leaks, masking
 *    would not have saved it — that is the decisive "metadata-only is structural" evidence.
 */
object NeedleHarness {

    /** Class 1 — secret-shaped; caught by `SecretMasker`. */
    const val SECRET_NEEDLE = "sk-ant-NDLsecret0123456789abcdefXY"

    /** Class 2 — plain content; deliberately matches NO masker pattern (no KEY/TOKEN/SECRET/Bearer/sk-/pk-/gh_/AKIA). */
    const val PLAIN_NEEDLE = "NDL-PLAIN-content-7Q4z9-marker-path"

    val bothNeedles: List<String> = listOf(SECRET_NEEDLE, PLAIN_NEEDLE)

    private fun payload(field: String): String = "[$field] $SECRET_NEEDLE :: $PLAIN_NEEDLE"

    /** Return a copy of [event] with both needles injected into every content-bearing field. */
    fun inject(event: StreamJsonEvent): StreamJsonEvent = when (event) {
        is AssistantEvent -> event.copy(message = injectMessage(event.message))
        is UserEvent -> event.copy(message = injectMessage(event.message))
        is ResultEvent -> event.copy(result = append(event.result, "result")) // ResultEvent.result is free text
        is RateLimitEvent -> event.copy(
            rateLimitInfo = buildJsonObject {
                event.rateLimitInfo?.forEach { (k, v) -> put(k, v) }
                put("needle", payload("rate_limit_info")) // a NON-whitelisted key → projector must not lift it
            },
        )
        is SystemEvent -> event // no free content the projector reads
    }

    private fun injectMessage(m: AgentMessage): AgentMessage = m.copy(content = m.content.map(::injectBlock))

    private fun injectBlock(b: ContentBlock): ContentBlock = when (b) {
        is TextBlock -> b.copy(text = append(b.text, "text"))
        is ThinkingBlock -> b.copy(thinking = append(b.thinking, "thinking"))
        is ToolUseBlock -> b.copy(
            input = buildJsonObject {
                b.input.forEach { (k, v) -> put(k, v) }
                put("needle", payload("tool_use.input")) // args/content, NOT the tool name
            },
        )
        is ToolResultBlock -> b.copy(content = JsonPrimitive(append((b.content as? JsonPrimitive)?.content, "tool_result.content")))
    }

    private fun append(existing: String?, field: String): String = (existing ?: "") + " " + payload(field)

    /** Assert NEITHER needle appears in [haystack] (a projected-event dump / DB-file bytes / API response / WS frame). */
    fun assertNoNeedle(haystack: String, where: String) {
        assertFalse(haystack.contains(SECRET_NEEDLE), "secret needle leaked into $where")
        assertFalse(
            haystack.contains(PLAIN_NEEDLE),
            "plain-content needle leaked into $where — structural metadata-only failed (masking would NOT have caught this)",
        )
    }

    fun assertNoNeedle(bytes: ByteArray, where: String) = assertNoNeedle(bytes.decodeToString(), where)
}
