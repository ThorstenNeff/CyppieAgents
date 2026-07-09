package com.tneff.cyppieagents.agentview

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps frozen `:core` wire events ([StreamJsonEvent], CYP-9) to the UI model ([AgentEvent]).
 * This is the single change-seam the renderer was built around (CYP-6): the renderer and
 * [foldEvent] never see the wire shape.
 *
 * Stateful by design — it remembers each `tool_use` block by id so that the later
 * `tool_result` (which carries only `tool_use_id`, not the tool name) can emit a complete
 * [AgentEvent.ToolCall] that [foldEvent] updates in place RUNNING → OK/ERROR. Use one instance
 * per stream/collection (see [MappingAgentSession], which creates a fresh one per collection).
 *
 * Masking note (D3 / Gate #3): events are expected **already masked** by the server mediator
 * before reaching the client. The compact summaries here are presentation only and additionally
 * truncate — they do not assume raw, secret-bearing payloads.
 */
class StreamJsonMapper {

    private val toolCalls = HashMap<String, AgentEvent.ToolCall>()
    private var seq = 0

    /**
     * CYP-335: [tsMs] is the server's stamp for [event] (`StoredAgentEvent.tsMs`) and dates every row this
     * wire event produces. The mapper never reads a clock — the time comes from the wire, so a replayed
     * history maps to the same times it did the first time.
     */
    fun map(event: StreamJsonEvent, tsMs: Long): List<AgentEvent> = when (event) {
        is SystemEvent ->
            if (event.subtype == "init") listOf(AgentEvent.Notice(idOf(event.uuid), systemNotice(event), tsMs))
            else emptyList()

        // Observability/spend signal — never part of the transcript (REPORT.md §3, "nicht in den Hub").
        is RateLimitEvent -> emptyList()

        is AssistantEvent -> event.message.content.flatMap { mapAssistantBlock(event, it, tsMs) }

        is UserEvent -> event.message.content.flatMap { mapUserBlock(event, it, tsMs) }

        is ResultEvent -> mapResult(event, tsMs)
    }

    private fun mapAssistantBlock(e: AssistantEvent, block: ContentBlock, tsMs: Long): List<AgentEvent> = when (block) {
        is TextBlock -> listOf(
            AgentEvent.AssistantText(
                id = idOf(e.messageId ?: e.uuid),
                text = block.text,
                // Partial-messages OFF (MVP): a turn arrives complete, so stopReason is set.
                // When token-streaming lands, deltas share message.id and fold concatenates.
                complete = e.message.stopReason != null,
                tsMs = tsMs,
            )
        )

        // No `thinking` kind in the testTag vocabulary (Test-Contract v0.4 §2) — dropped for MVP.
        // Flagged to PO as an optional additive AgentEvent.Thinking; revisit on decision.
        is ThinkingBlock -> emptyList()

        is ToolUseBlock -> {
            val call = AgentEvent.ToolCall(
                id = block.id,
                tool = block.name,
                summary = summarizeToolInput(block.input),
                status = ToolStatus.RUNNING,
                tsMs = tsMs,
            )
            toolCalls[block.id] = call
            listOf(call)
        }

        is ToolResultBlock -> emptyList() // tool_result normally arrives in a user event
    }

    private fun mapUserBlock(e: UserEvent, block: ContentBlock, tsMs: Long): List<AgentEvent> = when (block) {
        is ToolResultBlock -> buildList {
            val toolId = block.toolUseId
            val prior = toolId?.let { toolCalls[it] }
            if (prior != null) {
                // CYP-335: copying from `prior` carries the tool call's START time forward — this row is dated
                // by when the tool was invoked, not by when its result arrived. (foldEvent enforces the same
                // rule independently, for sources that don't go through this mapper.)
                val resolved = prior.copy(status = if (block.isError) ToolStatus.ERROR else ToolStatus.OK)
                toolCalls[toolId] = resolved
                add(resolved) // same id → foldEvent updates the tool-call row in place
            }
            add(
                AgentEvent.Result(
                    id = "result-" + idOf(toolId ?: e.uuid),
                    label = summarizeResult(block.content),
                    isError = block.isError,
                    // The result row is its own row and is dated by its own arrival.
                    tsMs = tsMs,
                )
            )
        }

        // CYP-326 #1: a PLATFORM-injected incoming message (injectedSource names the injector, e.g. the compact
        // orchestrator) → surface it as an IncomingSystem row so the operator sees the trigger. A plain replayed
        // user echo (injectedSource == null) stays dropped — the composer turn is already echoed client-side
        // (CYP-323), so this never double-echoes.
        is TextBlock ->
            if (e.injectedSource != null) {
                listOf(AgentEvent.IncomingSystem(id = idOf(e.uuid), text = block.text, tsMs = tsMs))
            } else {
                emptyList()
            }

        else -> emptyList()
    }

    private fun mapResult(e: ResultEvent, tsMs: Long): List<AgentEvent> =
        if (e.isSuccess) {
            // Turn end: the assistant text is already shown and complete; result.result duplicates it.
            emptyList()
        } else {
            listOf(AgentEvent.Notice(idOf(e.uuid), "Turn-Fehler" + (e.subtype?.let { ": $it" } ?: ""), tsMs))
        }

    private fun systemNotice(e: SystemEvent): String =
        "Session gestartet" + (e.model?.let { " · $it" } ?: "")

    /** Stable id, falling back to a deterministic per-mapper sequence when the wire id is absent. */
    private fun idOf(id: String?): String = id ?: "ev-${seq++}"

    private companion object {
        const val SUMMARY_MAX = 80
        const val RESULT_MAX = 120
        val PREFERRED_KEYS = listOf("command", "file_path", "path", "pattern", "query", "url", "description")

        fun summarizeToolInput(input: JsonObject): String {
            val key = PREFERRED_KEYS.firstOrNull { input.containsKey(it) } ?: input.keys.firstOrNull()
            return truncate(scalar(key?.let { input[it] }), SUMMARY_MAX)
        }

        fun summarizeResult(content: JsonElement?): String = truncate(scalar(content), RESULT_MAX)

        fun scalar(element: JsonElement?): String = when (element) {
            null -> ""
            is JsonPrimitive -> element.content
            else -> element.toString()
        }

        fun truncate(s: String, max: Int): String {
            val flat = s.replace('\n', ' ').trim()
            return if (flat.length <= max) flat else flat.take(max - 1) + "…"
        }
    }
}
