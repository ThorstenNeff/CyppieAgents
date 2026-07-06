package com.tneff.cyppieagents.report

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.MessageKind
import com.tneff.cyppieagents.model.MessageMeta
import com.tneff.cyppieagents.model.ReportSnapshot
import com.tneff.cyppieagents.model.ReportType
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Report folding (S16 / CYP-89). The reviewer's core: **content-free hard-enforcement** — a report
 * folds event/message METADATA only and can never carry a body. Plus the fold shapes per type.
 */
class ReportGeneratorTest {

    private val EVENT_NEEDLE = "sk-LEAK-EVENT-9999"
    private val MSG_NEEDLE = "SECRET-MESSAGE-BODY-7777"

    private class Fix {
        val sink = InMemoryEventSink(SystemTimeSource())
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend"), Agent("backend", "BE", Role.WORKER, "backend")),
            HubState.OPERATOR_ID, "default",
        )
        val hub = Hub(state, InMemoryMessageStore())
        val gen = ReportGenerator(sink, state, hub)
    }

    private fun ReportSnapshot.json() = CommJson.encodeToString(ReportSnapshot.serializer(), this)

    @Test
    fun reportsAreContentFree_noEventBodyOrMessageBodyLeaks() = runBlocking {
        val f = Fix()
        // Plant a "body" (a secret-shaped field) in event detail AND a message body. Neither must ever
        // surface in a report payload. Mutation: dump e.detail / message.body into a ReportItem → red.
        f.sink.appendBatch(listOf(
            EventDraft("frontend", "default", EventType.TURN_START, Severity.INFO, detail = buildJsonObject { put("secret", EVENT_NEEDLE) }),
            EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "c-1", detail = buildJsonObject { put("secret", EVENT_NEEDLE) }),
            EventDraft("backend", "default", EventType.LOG_DROPPED, Severity.INFO),
        ))
        f.hub.postAsAgent("frontend", "po-frontend", MSG_NEEDLE, MessageMeta(kind = MessageKind.STATUS))

        for (type in listOf(ReportType.STATUS, ReportType.DEFECTS, ReportType.USAGE)) {
            val built = f.gen.build(type, since = null, until = null)
            val json = ReportSnapshot("id", type, 0, "default", built.sources, built.window, built.sections).json()
            assertFalse(json.contains(EVENT_NEEDLE), "[$type] event body must never reach the report payload")
            assertFalse(json.contains(MSG_NEEDLE), "[$type] message body must never reach the report payload")
        }
    }

    @Test
    fun status_foldsActivityPerAgent_andCountsStatusMessages_metadataOnly() = runBlocking {
        val f = Fix()
        f.sink.appendBatch(listOf(
            EventDraft("frontend", "default", EventType.TURN_START, Severity.INFO),
            EventDraft("frontend", "default", EventType.RESULT_FINAL, Severity.INFO),
            EventDraft("backend", "default", EventType.AGENT_SPAWNED, Severity.INFO),
        ))
        f.hub.postAsAgent("frontend", "po-frontend", "ready", MessageMeta(kind = MessageKind.STATUS))
        val built = f.gen.build(ReportType.STATUS, null, null)
        val activity = built.sections.first { it.key == "activity" }.items
        assertTrue(activity.any { it.refLabel == "agent: frontend" && it.text.contains("2") }, "frontend has 2 activity events")
        assertTrue(activity.any { it.refLabel == "agent: backend" })
        val status = built.sections.first { it.key == "status" }.items
        assertTrue(status.any { it.refLabel == "agent: frontend" && it.text.contains("1 Status") })
    }

    @Test
    fun defects_foldsBySeverityType_andSurfacesLogDroppedGap() = runBlocking {
        val f = Fix()
        f.sink.appendBatch(listOf(
            EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "c-1"),
            EventDraft("backend", "default", EventType.ERROR_RATELIMIT, Severity.WARN),
            EventDraft("backend", "default", EventType.LOG_DROPPED, Severity.INFO),
            EventDraft("frontend", "default", EventType.TURN_START, Severity.INFO), // NOT a defect → excluded
        ))
        val items = f.gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items
        assertTrue(items.any { it.severity == Severity.ERROR && it.refLabel == "correlationId: c-1" })
        assertTrue(items.any { it.severity == Severity.WARN })
        // log.dropped is surfaced as an honest observation gap, not swallowed.
        assertTrue(items.any { it.refLabel == "log.dropped" }, "telemetry gap is reported, not hidden")
        // a turn.start is not a defect
        assertFalse(items.any { it.text.contains("turn") })
    }

    @Test
    fun usage_foldsAgentsAndChannels_counts() = runBlocking {
        val f = Fix()
        val setup = f.gen.build(ReportType.USAGE, null, null).sections.first { it.key == "setup" }.items
        assertTrue(setup.any { it.refLabel == "agents" && it.text.contains("3 Agenten") && it.text.contains("1 PO") })
        assertTrue(setup.any { it.refLabel == "channels" })
    }

    @Test
    fun report_scopedToActiveProject_doesNotAggregateForeignProjects() = runBlocking {
        // CYP-255 ③ — the shared Event-Log is a multi-project store; the report must aggregate ONLY the
        // ACTIVE project's events. Seed a defect in "default" (active) AND one in "other" → the report
        // includes the active defect, never the foreign one. Mutation: drop projectId from the query's
        // EventFilter → the "other" defect leaks into the active project's report → red.
        val f = Fix() // active project = "default"
        f.sink.appendBatch(listOf(
            EventDraft("backend", "default", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "defect-DEFAULT"),
            EventDraft("backend", "other", EventType.ERROR_TOOL, Severity.ERROR, correlationId = "defect-OTHER"),
        ))
        val items = f.gen.build(ReportType.DEFECTS, null, null).sections.first { it.key == "defects" }.items
        assertTrue(items.any { it.refLabel == "correlationId: defect-DEFAULT" }, "the active project's defect is aggregated")
        assertFalse(items.any { it.refLabel == "correlationId: defect-OTHER" }, "a foreign project's defect must NOT leak into the report (③)")
    }

    @Test
    fun window_labels_honestBoundaries() = runBlocking {
        val f = Fix()
        assertEquals("boot", f.gen.build(ReportType.STATUS, null, null).window.sinceLabel)
        assertEquals("since=100", f.gen.build(ReportType.STATUS, 100, 200).window.sinceLabel)
    }
}
