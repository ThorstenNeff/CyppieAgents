package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.EventSink
import com.tneff.cyppieagents.events.Page
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.ReportItem
import com.tneff.cyppieagents.model.ReportSection
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.ReportWindow
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity

/**
 * Folds the platform's READ sources into a report snapshot's sections (S16 / CYP-89). MVP observation is
 * **platform-internal** (events / comm / agents) — not Jira/Git/test truth.
 *
 * **Content-free HARD-ENFORCEMENT (Reviewer focus):** every [ReportItem] is composed from event/message
 * **metadata only** — the event `type`, `severity`, `agentId`, `correlationId`, and counts — and NEVER
 * from `Event.detail`, `Message.body`, or any free-text payload. There is no path here that reads a body
 * into a report, so the snapshot cannot leak content around the operator-gated event store.
 */
class ReportGenerator(
    private val eventSink: EventSink,
    private val state: HubState,
    private val hub: Hub,
    private val operatorId: String = HubState.OPERATOR_ID,
) {
    data class Built(val sources: List<String>, val window: ReportWindow, val sections: List<ReportSection>)

    suspend fun build(type: ReportType, since: Long?, until: Long?): Built = when (type) {
        ReportType.USAGE -> usage()
        ReportType.STATUS -> status(since, until)
        ReportType.DEFECTS -> defects(since, until)
    }

    // ---- USAGE: current setup (agents + channels), no events ----
    private fun usage(): Built {
        val agents = state.agents
        val po = agents.count { it.role == Role.PO }
        val workers = agents.count { it.role == Role.WORKER }
        val hubChannels = state.channels.count { it.kind == com.tneff.cyppieagents.model.ChannelKind.HUB }
        return Built(
            sources = listOf("agents", "channels"),
            window = ReportWindow(),
            sections = listOf(
                ReportSection(
                    "setup", "Setup",
                    listOf(
                        ReportItem("${agents.size} Agenten registriert ($po PO, $workers Worker)", refLabel = "agents"),
                        ReportItem("$hubChannels Hub-and-Spoke-Kanäle aktiv", refLabel = "channels"),
                    ),
                ),
            ),
        )
    }

    // ---- STATUS: observed activity (events + STATUS messages) ----
    private suspend fun status(since: Long?, until: Long?): Built {
        val events = query(since, until)
        // Per agent: count the activity events we observed (metadata only — never the turn/result content).
        val activityTypes = setOf(
            EventType.TURN_START, EventType.TURN_END, EventType.RESULT_FINAL,
            EventType.AGENT_SPAWNED, EventType.AGENT_RESTARTED, EventType.AGENT_STOPPED,
        )
        val byAgent = events.filter { it.type in activityTypes }.groupBy { it.agentId }
        val activity = byAgent.map { (agent, evs) ->
            ReportItem("$agent: ${evs.size} Aktivitäts-Event(s) beobachtet", refLabel = "agent: $agent")
        }.sortedBy { it.refLabel }
        // STATUS messages: the FACT one was posted, by whom — never the body.
        val statusMsgs = hub.inbox(operatorId, since)
            .filter { (until == null || it.ts < until) && it.meta?.kind == MessageKind.STATUS }
            .groupingBy { it.from }.eachCount()
        val statusItems = statusMsgs.map { (from, n) ->
            ReportItem("$from: $n Status-Meldung(en)", refLabel = "agent: $from")
        }.sortedBy { it.refLabel }
        return Built(
            sources = listOf("events:turn.*/result.final/agent.*", "inbox:STATUS"),
            window = window(since, until),
            sections = listOf(
                ReportSection("activity", "Aktivität (beobachtet)", activity.ifEmpty { listOf(ReportItem("keine Aktivität im Fenster beobachtet")) }),
                ReportSection("status", "Status-Meldungen", statusItems),
            ),
        )
    }

    // ---- DEFECTS: advisory register (error/timeout/process.exit/log.dropped) ----
    private suspend fun defects(since: Long?, until: Long?): Built {
        val events = query(since, until)
        val defectTypes = setOf(
            EventType.ERROR_MODEL, EventType.ERROR_TOOL, EventType.ERROR_RATELIMIT,
            EventType.TIMEOUT, EventType.PROCESS_EXIT, EventType.WS_DISCONNECT, EventType.STALL_ESCALATED,
        )
        val defectItems = events.filter { it.type in defectTypes }.map { e ->
            // Composed from the type + severity + a non-sensitive ref — NEVER e.detail.
            ReportItem("${labelOf(e.type)} beobachtet", severity = e.severity, refLabel = refOf(e))
        }
        // CYP-364 (Root-C): `log.dropped` is PLATFORM telemetry — EventRecorder emits it with
        // projectId=[EventRecorder.PLATFORM] (a server-wide back-pressure counter), NOT under any user
        // project. So the active-project [query] above FILTERED IT OUT, and the gap line never appeared:
        // an observation GAP silently read as an all-clear. Query the platform telemetry lane directly so
        // the drops surface regardless of which project is active. (No ③ cross-project leak: PLATFORM is not
        // a user project, and only a COUNT crosses — never project content.)
        val dropEvents = eventSink.query(
            EventFilter(since = since, until = until, type = EventType.LOG_DROPPED, projectId = EventRecorder.PLATFORM),
            Page(limit = 2000),
        ).events
        // CYP-353 (Root-B): SUM the discarded-EVENT deltas — do NOT count the `log.dropped` REPORTS. One
        // report carries a whole batch's `dropped` delta (EventRecorder.reportDropsIfAny), so counting reports
        // under-states the gap (a single report can mean thousands of dropped events). The `dropped` field is a
        // numeric telemetry COUNT — not a body/free-text/content — so reading it is the ONE narrow, justified
        // read of `detail` here: the content-free rule guards CONTENT egress, and a count is not content.
        val dropped = dropEvents.sumOf { (it.detail["dropped"] as? JsonPrimitive)?.longOrNull ?: 0L }
        val gapItems = if (dropped > 0) {
            listOf(ReportItem("Telemetrie-Lücke: $dropped Event(s) verworfen (log.dropped)", severity = Severity.INFO, refLabel = "log.dropped"))
        } else {
            emptyList()
        }
        return Built(
            sources = listOf("events:error.*/timeout/process.exit/log.dropped"),
            window = window(since, until),
            sections = listOf(
                ReportSection("defects", "Beobachtete Defekte/Lücken (advisory)", defectItems + gapItems),
            ),
        )
    }

    private suspend fun query(since: Long?, until: Long?): List<Event> =
        // CYP-255 ③ — scope the report aggregation to the ACTIVE project (the shared Event-Log is a
        // multi-project store; an unscoped query aggregates over ALL projects → cross-project egress once
        // non-boot projects are populated — today masked only by the fail-closed emptiness). Server-side
        // from the hub's active pointer (never a caller param), like /api/events + /ws/events (CYP-102).
        eventSink.query(EventFilter(since = since, until = until, projectId = state.activeProjectId), Page(limit = 2000)).events

    private fun window(since: Long?, until: Long?) =
        ReportWindow(sinceLabel = since?.let { "since=$it" } ?: "boot", untilLabel = until?.let { "until=$it" } ?: "now")

    private fun refOf(e: Event): String = when {
        e.correlationId != null -> "correlationId: ${e.correlationId}"
        else -> "agent: ${e.agentId}"
    }

    private fun labelOf(t: EventType): String = when (t) {
        EventType.ERROR_MODEL -> "Modell-Fehler"
        EventType.ERROR_TOOL -> "Tool-Fehler"
        EventType.ERROR_RATELIMIT -> "Rate-Limit"
        EventType.TIMEOUT -> "Timeout"
        EventType.PROCESS_EXIT -> "Prozess-Ende"
        EventType.WS_DISCONNECT -> "WS-Abbruch"
        EventType.STALL_ESCALATED -> "Stall eskaliert"
        else -> t.wire
    }
}
