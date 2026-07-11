package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.SessionObserver
import com.tneff.cyppieagents.model.AssistantEvent
import com.tneff.cyppieagents.model.RateLimitEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.ToolResultBlock
import com.tneff.cyppieagents.model.ToolUseBlock
import com.tneff.cyppieagents.model.UserEvent
import com.tneff.cyppieagents.model.WireEvent
import com.tneff.cyppieagents.model.WireEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * S5 / G4 — the bridge's self-report. The [SessionObserver] seam (S4.0, `null` until now) taps the CC
 * session's **already-masked** stream (Gate #3 runs in the shared core BEFORE the observer) and relays the
 * structured signals the text-only `WireSend` drops — rate-limit + tool events — as content-free
 * [WireEvent]s over the [WireLink]. This is what makes the bridge's declared `rateLimitSignal`/
 * `toolGranularity` = LIMITED honest (S1/E2.8 verdict): the server records them into the Event-Log
 * (source=remote), restoring the Warden stall-net + tool depth for the remote agent.
 *
 * It carries ONLY content-free signals: a tool NAME (never the masked input/output), and the rate-limit
 * fields — sent verbatim; the SERVER whitelists + size-caps them (G4-5), so the bridge is not trusted to.
 */
class WireReportingObserver(
    private val link: WireLink,
    private val scope: CoroutineScope,
) : SessionObserver {

    private val log = LoggerFactory.getLogger("remote.report")

    override fun onEvent(agentId: String, sessionId: String?, correlationId: String?, event: StreamJsonEvent) {
        val frames: List<WireEvent> = when (event) {
            is RateLimitEvent -> event.rateLimitInfo?.let { listOf(WireEvent(WireEventType.RATE_LIMIT, rateLimit = flatten(it))) } ?: emptyList()
            is AssistantEvent -> event.message.content.filterIsInstance<ToolUseBlock>().map { WireEvent(WireEventType.TOOL_CALL, tool = it.name) }
            is UserEvent -> event.message.content.filterIsInstance<ToolResultBlock>().map { WireEvent(WireEventType.TOOL_RESULT) }
            else -> emptyList()
        }
        for (f in frames) scope.launch {
            runCatching { link.send(f) }.onFailure { log.warn("self-report send failed for {}: {}", agentId, it.message) }
        }
    }

    // The bridge doesn't self-report turn lifecycle / process exit for caps — only rate-limit + tool depth.
    // CYP-351: the exit status of a process in the user's infrastructure is only ever what the bridge chooses
    // to report; the hub must not infer a run state from a signal that never crosses the trust boundary.
    override fun onTurnStart(agentId: String, sessionId: String?, correlationId: String) {}
    override fun onProcessExit(agentId: String, sessionId: String?, exitCode: Int?) {}
    override fun onStopped(agentId: String) {}

    /** Flatten a JSON object's primitive fields to `Map<String,String>` for the wire; the server whitelists. */
    private fun flatten(o: JsonObject): Map<String, String> =
        o.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }.toMap()
}
