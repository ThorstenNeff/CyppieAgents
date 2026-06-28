package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scaffold proof for the Mediator-Aufsicht Sense stage (07/S11, CYP-60):
 *  - a [Signal] becomes an Event in the same bus, with an unknown 07 type preserved via `rawType`;
 *  - the [Scanner] is a real `subscribe` consumer: stream → detector → signal back into the bus;
 *  - a detector that finds nothing emits nothing (mutation guard);
 *  - a throwing detector is isolated and never starves its peers.
 *
 * "Read-only + signal-emit, no write toward an agent" is guaranteed structurally, not asserted: the
 * [Scanner]/[Detector]/[SignalSink] constructors take no connector/session — there is no API here
 * that could touch an agent. CYP-62 (Warden/Actuator) is the only thing that gets that authority.
 */
class ScannerScaffoldTest {

    private fun rateLimit(agent: String, status: String): EventDraft = EventDraft(
        agentId = agent,
        projectId = "default",
        type = EventType.ERROR_RATELIMIT,
        severity = Severity.WARN,
        detail = buildJsonObject { put("status", status) },
    )

    /** A stand-in for the CYP-61 stall detector: fires only on a throttle marker, ignores all else. */
    private class ThrottleDetector : Detector {
        override fun onEvent(e: Event): Signal? {
            if (e.type != EventType.ERROR_RATELIMIT) return null
            val status = e.detail["status"]?.jsonPrimitive?.content
            if (status != "blocked" && status != "rejected") return null
            return Signal(
                type = "stall.suspected",
                agentId = e.agentId,
                projectId = e.projectId,
                correlationId = e.correlationId,
                evidence = buildJsonObject { put("trigger", "error.ratelimit"); put("status", status) },
            )
        }
    }

    private suspend fun snapshot(sink: InMemoryEventSink): List<Event> =
        sink.query(EventFilter.ALL, Page(limit = 1000)).events

    private suspend fun awaitSignal(sink: InMemoryEventSink, timeoutMs: Long = 5_000): List<Event> =
        withTimeout(timeoutMs) {
            var seen = snapshot(sink)
            while (seen.none { (it.rawType ?: it.type.wire) == "stall.suspected" }) {
                delay(20)
                seen = snapshot(sink)
            }
            seen
        }

    @Test
    fun signalEmittedAsEvent_firstClassStallType() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sink = InMemoryEventSink(ManualTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        try {
            EventLogSignalSink(recorder).emit(
                Signal(
                    type = "stall.suspected",
                    agentId = "backend",
                    projectId = "default",
                    correlationId = "run-7",
                    evidence = buildJsonObject { put("status", "blocked") },
                ),
            )
            val ev = awaitSignal(sink).single { (it.rawType ?: it.type.wire) == "stall.suspected" }
            // CYP-64: stall.suspected is now a first-class EventType → no rawType fallback needed.
            assertEquals(EventType.STALL_SUSPECTED, ev.type)
            assertEquals(null, ev.rawType)
            assertEquals(Severity.WARN, ev.severity) // ".suspected" → warn (07 §4)
            assertEquals("backend", ev.agentId)
            assertEquals("default", ev.projectId)
            assertEquals("run-7", ev.correlationId)
            assertEquals("blocked", ev.detail["status"]?.jsonPrimitive?.content)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun scanner_streamToDetectorToSignal_endToEnd() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sink = InMemoryEventSink(ManualTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        try {
            Scanner(sink, listOf(ThrottleDetector()), EventLogSignalSink(recorder), scope).start()
            // The sink stream has no replay: a trigger appended before the collector registers is
            // missed. Re-append a throttle beat until the real end condition (a stall.suspected in the
            // bus) holds — awaiting the actual condition, not a proxy.
            val events = withTimeout(8_000) {
                var all = snapshot(sink)
                while (all.none { (it.rawType ?: it.type.wire) == "stall.suspected" }) {
                    sink.append(rateLimit("backend", "blocked"))
                    delay(50)
                    all = snapshot(sink)
                }
                all
            }
            val signal = events.first { (it.rawType ?: it.type.wire) == "stall.suspected" }
            assertEquals("backend", signal.agentId)
            assertEquals("error.ratelimit", signal.detail["trigger"]?.jsonPrimitive?.content)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun detectorFindingNothing_emitsNoSignal() = runBlocking {
        // Mutation guard: if scan() emitted unconditionally (dropped the `?: continue`), a routine
        // beat would produce a phantom signal. It must not.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sink = InMemoryEventSink(ManualTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        try {
            val scanner = Scanner(sink, listOf(ThrottleDetector()), EventLogSignalSink(recorder), scope)
            // Drive scan() directly (race-free): a routine "allowed_warning" beat → no finding.
            scanner.scan(sink.append(rateLimit("backend", "allowed_warning")))
            delay(200) // give any (erroneous) emission time to land
            assertNull(snapshot(sink).firstOrNull { (it.rawType ?: it.type.wire) == "stall.suspected" }, "routine beat must not signal")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun throwingDetectorIsIsolated_peerStillSignals() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sink = InMemoryEventSink(ManualTimeSource())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        try {
            val boom = Detector { throw IllegalStateException("buggy detector") }
            val scanner = Scanner(sink, listOf(boom, ThrottleDetector()), EventLogSignalSink(recorder), scope)
            // Mutation guard: without the try/catch in scan(), `boom` would abort the fan-out and the
            // good detector's signal would be lost.
            scanner.scan(sink.append(rateLimit("frontend", "rejected")))
            val events = awaitSignal(sink)
            assertTrue(events.any { (it.rawType ?: it.type.wire) == "stall.suspected" && it.agentId == "frontend" })
        } finally {
            scope.cancel()
        }
    }
}
