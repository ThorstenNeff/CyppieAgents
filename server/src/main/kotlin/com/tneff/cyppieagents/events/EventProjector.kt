package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityGate
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Projects masked [StreamJsonEvent]s (and lifecycle/comm moments) into content-free [EventDraft]s
 * (PRD §3.5, ST3/CYP-37). This is where the **metadata-only** Non-Goal (PRD §2) is enforced
 * **structurally**: the projection reads only non-content fields — `toolName`, `toolUseId`, `isError`,
 * usage numbers, durations — and **never** `TextBlock.text`, `ThinkingBlock.thinking`,
 * `ToolUseBlock.input`, `ToolResultBlock.content` or `ResultEvent.result`. The masking at the tap
 * (Gate #3) is the belt; metadata-only here is the suspenders. (Needle-Absence: CYP-44 §4.)
 *
 * `context.usage` is delegated to the [ContextUsageBander] (CYP-36): only band/compact crossings are
 * persisted. `correlationId` is set by the caller at `turn.start` and carried to `result.final`
 * (PO decision c).
 */
class EventProjector(
    private val bander: ContextUsageBander,
    private val projectId: String,
    /**
     * Per-agent connector capabilities resolver (CYP-121/122, Doc 10 §3). **Null = no capability system
     * configured** → no gating (legacy behaviour for installs/tests). When wired, two fidelity dimensions
     * gate event-log depth: `toolGranularity` (tool.call/tool.result) and `structuredUsage`
     * (context.usage). A resolver that returns null for an agent (registry miss) **fails closed** to OFF —
     * never assumed AVAILABLE (F2). CYP-122: a DEGRADED dimension runs reduced (tools still emit; usage is
     * coarse — compact-threshold only), only OFF/unknown suppresses.
     */
    private val capabilities: ((agentId: String) -> Capabilities?)? = null,
) {
    private fun mode(agentId: String, capability: CapabilityGate.EnforcedCapability): CapabilityGate.CapabilityMode {
        val resolve = capabilities ?: return CapabilityGate.CapabilityMode.ENABLED // no system → enabled (legacy)
        return CapabilityGate.mode(capability, resolve(agentId)) // resolve()==null → fail-closed OFF
    }
    /** Stream events → drafts. May produce 0 (e.g. system/init, text-only assistant), 1, or many. */
    fun project(
        agentId: String,
        sessionId: String?,
        correlationId: String?,
        event: StreamJsonEvent,
    ): List<EventDraft> = when (event) {
        // toolGranularity gate (CYP-121/122): OFF (or unknown) → no tool events; ENABLED/DEGRADED → emit
        // (a DEGRADED connector just produces fewer tool blocks — honestly thinner, never faked).
        is AssistantEvent -> if (mode(agentId, CapabilityGate.EnforcedCapability.TOOL_GRANULARITY) == CapabilityGate.CapabilityMode.OFF) emptyList()
        else event.message.content.filterIsInstance<ToolUseBlock>().map { tu ->
            draft(agentId, sessionId, correlationId, EventType.TOOL_CALL, Severity.INFO) {
                put("toolName", tu.name)
                put("toolUseId", tu.id) // NOT tu.input
            }
        }

        is UserEvent -> if (mode(agentId, CapabilityGate.EnforcedCapability.TOOL_GRANULARITY) == CapabilityGate.CapabilityMode.OFF) emptyList()
        else event.message.content.filterIsInstance<ToolResultBlock>().map { tr ->
            draft(agentId, sessionId, correlationId, EventType.TOOL_RESULT, if (tr.isError) Severity.WARN else Severity.INFO) {
                tr.toolUseId?.let { put("toolUseId", it) }
                put("isError", tr.isError) // NOT tr.content
            }
        }

        is ResultEvent -> buildList {
            add(
                draft(agentId, sessionId, correlationId, EventType.RESULT_FINAL, if (event.isError) Severity.ERROR else Severity.INFO) {
                    event.subtype?.let { put("subtype", it) }
                    put("isError", event.isError)
                    event.durationMs?.let { put("durationMs", it) }
                    event.numTurns?.let { put("numTurns", it) }
                    event.totalCostUsd?.let { put("totalCostUsd", it) } // NOT event.result (text)
                },
            )
            if (event.isError) {
                add(
                    draft(agentId, sessionId, correlationId, EventType.ERROR_MODEL, Severity.ERROR) {
                        put("errorClass", event.subtype ?: "error") // a class, not raw error text
                    },
                )
            }
            // context.usage: numbers only, persisted only on a band/compact crossing. structuredUsage
            // gate (CYP-121/122): OFF/unknown → no context.usage (don't band on numbers we don't have);
            // ENABLED → full banding; DEGRADED → coarse — only the compact-threshold crossing, not every
            // band step (Doc 10 §4 col B: B's token tracking is too coarse to trust the fine bands).
            val snapshot = UsageSnapshot.fromUsageJson(event.usage)
            when (mode(agentId, CapabilityGate.EnforcedCapability.STRUCTURED_USAGE)) {
                CapabilityGate.CapabilityMode.OFF -> {}
                CapabilityGate.CapabilityMode.ENABLED ->
                    addAll(bander.onUsage(agentId, projectId, snapshot, sessionId, correlationId))
                CapabilityGate.CapabilityMode.DEGRADED ->
                    addAll(bander.onUsage(agentId, projectId, snapshot, sessionId, correlationId, coarse = true))
            }
        }

        is RateLimitEvent -> listOf(
            draft(agentId, sessionId, correlationId, EventType.ERROR_RATELIMIT, Severity.WARN) {
                // Whitelist of known numeric/status fields — never the whole object.
                val info = event.rateLimitInfo
                for (key in RATE_LIMIT_KEYS) {
                    (info?.get(key) as? JsonPrimitive)?.let { put(key, it) }
                }
            },
        )

        is SystemEvent -> emptyList() // session binding is handled in the connector; no projected event
    }

    /** A `turn.start` for an injected work-run; the caller mints [correlationId] and carries it forward. */
    fun turnStart(agentId: String, sessionId: String?, correlationId: String?) =
        draft(agentId, sessionId, correlationId, EventType.TURN_START, Severity.INFO) {}

    fun processExit(agentId: String, sessionId: String?, exitCode: Int?) =
        draft(agentId, sessionId, null, EventType.PROCESS_EXIT, if ((exitCode ?: 0) != 0) Severity.WARN else Severity.INFO) {
            exitCode?.let { put("exitCode", it) }
        }

    fun agentSpawned(agentId: String, worktree: String) =
        draft(agentId, null, null, EventType.AGENT_SPAWNED, Severity.INFO) { put("worktree", worktree) }

    fun agentStopped(agentId: String) =
        draft(agentId, null, null, EventType.AGENT_STOPPED, Severity.INFO) {}

    /** `agent.restarted`: a lifecycle restart respawned the agent in its worktree (CYP-73). */
    fun agentRestarted(agentId: String) =
        draft(agentId, null, null, EventType.AGENT_RESTARTED, Severity.INFO) {}

    /** `comm.sent`: a message the router posted on the agent's behalf — metadata only, NO body. */
    fun commSent(agentId: String, channelId: String, kind: MessageKind?) =
        draft(agentId, null, null, EventType.COMM_SENT, Severity.INFO) {
            put("from", agentId)
            put("channel", channelId)
            kind?.let { put("kind", it.name) }
        }

    /**
     * `comm.received` (CYP-132): a message DELIVERED as inbound into [recipientId]'s session — metadata
     * only (recipient / message id / channel), **never the body**, which legitimately enters only the
     * recipient's connector session via `sendTurn`. The content-free "delivered" marker.
     */
    fun commReceived(recipientId: String, messageId: String, channelId: String) =
        draft(recipientId, null, null, EventType.COMM_RECEIVED, Severity.INFO) {
            put("to", recipientId)
            put("messageId", messageId)
            put("channel", channelId)
        }

    private inline fun draft(
        agentId: String,
        sessionId: String?,
        correlationId: String?,
        type: EventType,
        severity: Severity,
        detail: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): EventDraft = EventDraft(
        agentId = agentId,
        projectId = projectId,
        type = type,
        severity = severity,
        sessionId = sessionId,
        correlationId = correlationId,
        detail = buildJsonObject(detail),
    )

    private companion object {
        // rate_limit_info fields worth recording — the REAL wire schema (CYP-59 spike, CLI 2.1.193/195,
        // camelCase), NOT the earlier synthetic-corpus snake_case. `status` is the throttle discriminator
        // (blocked/rejected vs allowed/allowed_warning). `overageStatus` is DELIBERATELY excluded: live it
        // is "rejected" even on a perfectly healthy agent (overage-billing availability, not a throttle).
        val RATE_LIMIT_KEYS = listOf("status", "resetsAt", "rateLimitType", "retryAfter")
    }
}
