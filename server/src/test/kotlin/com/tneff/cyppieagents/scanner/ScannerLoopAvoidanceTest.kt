package com.tneff.cyppieagents.scanner

import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Loop-avoidance (07 §3, Reviewer pre-merge): the [Scanner] subscribes to the whole bus and emits
 * Signals back into it, so a signal-type event must NOT be re-dispatched to detectors — otherwise a
 * detector reacting to it would retrigger the loop. The guard lives once at the Scanner seam
 * ([SignalVocabulary]), so it covers every detector and can't be forgotten by a future one.
 */
class ScannerLoopAvoidanceTest {

    private class CapturingSignalSink : SignalSink {
        val emitted = CopyOnWriteArrayList<Signal>()
        override suspend fun emit(signal: Signal) { emitted.add(signal) }
    }

    /** A deliberately greedy detector: emits on EVERY event it is handed — the worst case for loops. */
    private val greedy = Detector { e ->
        Signal(type = "stall.suspected", agentId = e.agentId, projectId = e.projectId, evidence = JsonObject(emptyMap()))
    }

    private var seq = 0L
    private fun signalEvent(wire: String): Event = Event(
        id = "s${seq++}", ts = 0, seq = seq, agentId = "backend", projectId = "default",
        type = EventType.UNKNOWN, rawType = wire, severity = Severity.WARN, detail = JsonObject(emptyMap()),
    )
    private fun domainEvent(): Event = Event(
        id = "d${seq++}", ts = 0, seq = seq, agentId = "backend", projectId = "default",
        type = EventType.ERROR_RATELIMIT, severity = Severity.WARN, detail = JsonObject(emptyMap()),
    )

    @Test
    fun signalTypeEvents_areNotReDispatched_butDomainEventsAre() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val cap = CapturingSignalSink()
            val scanner = Scanner(InMemoryEventSink(ManualTimeSource()), listOf(greedy), cap, scope)
            // Mutation guard: dropping the SignalVocabulary filter in scan() lets these be dispatched
            // → the greedy detector re-emits → emitted is non-empty → red (self-retrigger proven).
            for (wire in SignalVocabulary.TYPES) scanner.scan(signalEvent(wire))
            assertTrue(cap.emitted.isEmpty(), "signal-type events must never reach detectors (loop-avoidance)")

            // The filter must not over-block: a real domain event still fans out and produces a signal.
            scanner.scan(domainEvent())
            assertEquals(1, cap.emitted.size, "domain events must still be dispatched")
        } finally {
            scope.cancel()
        }
    }

    /**
     * Guards the **forward-looking** family promise (07 §4): a future watcher's signal type matches
     * `SignalVocabulary` only via the suffix/prefix family, NOT the explicit [SignalVocabulary.TYPES]
     * list — so this test uses synthetic types that are deliberately absent from TYPES. Without it the
     * family branch is vacuum-tested (every other test uses a type already in TYPES, double-covering
     * it). Mutation: drop the suffix/prefix branch in `isSignal` → these become non-signals →
     * `isSignal` assert fails AND the scanner re-dispatches them → red.
     */
    @Test
    fun futureFamilyTypes_notInExplicitList_areStillSignalsAndNotReDispatched() = runBlocking {
        // Synthetic types a future watcher (budget/security/…) would emit; none are in the explicit list.
        val familyOnly = listOf("budget.escalated", "budget.recovered", "xyz.suspected", "nudge.retried")
        for (t in familyOnly) {
            assertFalse(t in SignalVocabulary.TYPES, "$t must NOT be in the explicit list (tests the family branch)")
            assertTrue(SignalVocabulary.isSignal(t), "$t must be a signal via the suffix/prefix family")
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val cap = CapturingSignalSink()
            val scanner = Scanner(InMemoryEventSink(ManualTimeSource()), listOf(greedy), cap, scope)
            for (t in familyOnly) scanner.scan(signalEvent(t))
            assertTrue(cap.emitted.isEmpty(), "family-only signal types must not be re-dispatched (forward-looking loop-avoidance)")

            // Sanity: a domain type that merely *contains* a family word but doesn't match is still dispatched.
            assertFalse(SignalVocabulary.isSignal("tool.call"))
            scanner.scan(domainEvent())
            assertEquals(1, cap.emitted.size)
        } finally {
            scope.cancel()
        }
    }
}
