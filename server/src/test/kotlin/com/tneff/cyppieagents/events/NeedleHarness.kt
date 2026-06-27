package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.AgentMessage
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
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
 * Reusable two-class needle-injection harness for the Event-Log metadata-only proof (CYP-44 §4,
 * lands end-to-end with the CYP-37 projector + corpus). The §4 test injects these needles into a
 * replayed `StreamJsonEvent` corpus, runs it through the real tap→projector→sink, then greps the
 * three egress surfaces — **SQLite DB-file bytes + `/api/events` response + `/ws/events` stream** —
 * and asserts BOTH needles are absent in all three.
 *
 * The two classes are the whole point (see [NeedleHarnessSelfTest]):
 *  - [SECRET_NEEDLE] is secret-shaped → `SecretMasker` redacts it (proves the masking belt, Gate #3).
 *  - [PLAIN_NEEDLE] is innocuous content the masker does NOT touch → only a *structural* metadata-only
 *    projection can keep it out of `Event.detail`. If the plain needle leaks, masking would not have
 *    saved it: that is the decisive "metadata-only is structural, not just masked" evidence.
 */
object NeedleHarness {

    /** Class 1 — secret-shaped (`sk-ant-…`); caught by `SecretMasker`. */
    const val SECRET_NEEDLE = "sk-ant-NDLsecret0123456789abcdefXY"

    /** Class 2 — plain content; deliberately matches NO masker pattern (no KEY/TOKEN/SECRET/Bearer/sk-/pk-/gh_/AKIA). */
    const val PLAIN_NEEDLE = "NDL-PLAIN-content-7Q4z9-marker-path"

    val bothNeedles: List<String> = listOf(SECRET_NEEDLE, PLAIN_NEEDLE)

    /** A value that carries both needles, tagged with the source field so leaks are traceable. */
    private fun payload(field: String): String = "[$field] $SECRET_NEEDLE :: $PLAIN_NEEDLE"

    /** Assistant turn with both needles in EVERY content-bearing block (text/thinking/tool_use.input/tool_result.content). */
    fun assistantWithNeedles(sessionId: String = "s-needle"): AssistantEvent = AssistantEvent(
        message = AgentMessage(
            id = "m-needle",
            role = "assistant",
            content = listOf(
                TextBlock(text = payload("text")),
                ThinkingBlock(thinking = payload("thinking")),
                ToolUseBlock(
                    id = "toolu_ndl",
                    name = "Bash",
                    input = buildJsonObject { put("command", payload("tool_use.input")) },
                ),
                ToolResultBlock(
                    toolUseId = "toolu_ndl",
                    content = JsonPrimitive(payload("tool_result.content")),
                ),
            ),
        ),
        sessionId = sessionId,
    )

    /** User turn carrying needles in the message text (the `Message.body` egress class). */
    fun userWithNeedles(sessionId: String = "s-needle"): UserEvent = UserEvent(
        message = AgentMessage(role = "user", content = listOf(TextBlock(text = payload("user.text")))),
        sessionId = sessionId,
    )

    /**
     * All needle-bearing replay events. When CYP-37 lands its canonical NDJSON corpus of real
     * `StreamJsonEvent` shapes, fold those shapes in here (inject [payload] into their content
     * fields) so the §4 proof runs over realistic events, not just these two synthetic ones.
     */
    fun needleEvents(): List<StreamJsonEvent> = listOf(assistantWithNeedles(), userWithNeedles())

    /** Assert NEITHER needle appears in [haystack] (a DB-file dump / API response / WS frame). [where] labels the surface. */
    fun assertNoNeedle(haystack: String, where: String) {
        assertFalse(haystack.contains(SECRET_NEEDLE), "secret needle leaked into $where")
        assertFalse(
            haystack.contains(PLAIN_NEEDLE),
            "plain-content needle leaked into $where — structural metadata-only failed (masking would NOT have caught this)",
        )
    }

    /** ByteArray overload for grepping raw SQLite file bytes. */
    fun assertNoNeedle(bytes: ByteArray, where: String) = assertNoNeedle(bytes.decodeToString(), where)
}
