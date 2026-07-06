package com.tneff.cyppieagents.demo

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.events.EventDraft
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.EventRecorder
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.ManualTimeSource
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.scanner.EventLogSignalSink
import com.tneff.cyppieagents.scanner.Scanner
import com.tneff.cyppieagents.scanner.StallDetector
import com.tneff.cyppieagents.warden.MediatorActuator
import com.tneff.cyppieagents.warden.StallPolicy
import com.tneff.cyppieagents.warden.Warden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S11 end-to-end demo (CYP-71, doc §6 "wiederholbare Demo") — the whole Sense→Decide→Act loop wired
 * from the **real** components and driven by **injected events**, so it is deterministic and burns no
 * API quota (no real throttle, no real agent). It doubles as the integration regression net: the unit
 * tests prove each piece; this proves they compose into the loop and produce the right Live-Tail.
 *
 * Wiring (identical classes to `BootOrchestrator`): `InMemoryEventSink` ← `EventRecorder` ← shared
 * `EventLogSignalSink`; `Scanner`[`StallDetector`]; `Warden`[`StallPolicy`]; `MediatorActuator` over a
 * stub `ConnectorSession`. The orchestration that `StallSweeper`/`StallPolicyRunner`/the subscribe
 * loops do at runtime (sweep / tick / dispatch / onActivity) is invoked here step-by-step against a
 * manual clock — the same methods, no real 30/60/120/240 s waits.
 */
class S11StallLoopE2eTest {

    /** Captures injected nudges; no real agent session is needed for the demo. */
    private class StubSession(override val agentId: String) : ConnectorSession {
        val nudges = CopyOnWriteArrayList<String>()
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) { nudges.add(turn.text) }
        override fun close() {}
    }

    private class Harness {
        val agent = "backend"
        var nowMs = 1_000_000L
        val timeSource = ManualTimeSource()
        val sink = InMemoryEventSink(timeSource)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val recorder = EventRecorder(sink, scope).also { it.start() }
        val signalSink = EventLogSignalSink(recorder)
        val session = StubSession(agent)
        val sessions = ConnectorSessions().apply { register(session) }
        val actuator = MediatorActuator({ sessions }, signalSink, projectId = "default")
        val detector = StallDetector() // default T = 60s
        val scanner = Scanner(sink, listOf(detector), signalSink, scope)
        val policy = StallPolicy(clock = { nowMs }, actuator = actuator, signals = signalSink)
        val warden = Warden(sink, listOf(policy), actuator, scope)

        private fun clockTo(t: Long) { nowMs = t; timeSource.clock = t }

        /** Inject a rate-limit throttle onto the bus and let the Scanner arm the detector (Sense). */
        suspend fun injectThrottle(status: String) {
            clockTo(nowMs)
            val ev = sink.append(
                EventDraft(agent, "default", EventType.ERROR_RATELIMIT, Severity.WARN,
                    detail = buildJsonObject { put("status", status) }),
            )
            scanner.scan(ev) // what the Scanner's subscribe loop delivers to detectors
        }

        /** Advance time, run the detector sweep, and route any stall.suspected to the policy (Decide). */
        suspend fun advanceAndSweep(toMs: Long) {
            clockTo(toMs)
            for (signal in detector.sweep(nowMs)) signalSink.emit(signal) // StallSweeper's job
            // The Warden is a separate bus consumer; deliver the freshly-emitted signal events to it.
            for (e in awaitNew { (it.rawType ?: it.type.wire) == StallDetector.SIGNAL_TYPE }) warden.dispatch(e)
        }

        /** Advance time and tick the policy → backoff nudge or escalation (Act). */
        suspend fun advanceAndTick(toMs: Long) { clockTo(toMs); policy.tick() }

        /** Inject real agent activity (turn/tool) → recovery feed (StallPolicyRunner's job). */
        suspend fun injectActivity() {
            clockTo(nowMs)
            sink.append(EventDraft(agent, "default", EventType.TOOL_CALL, Severity.INFO,
                detail = buildJsonObject { put("toolName", "Bash") }))
            policy.onActivity(agent)
        }

        private val dispatched = HashSet<Long>()
        private suspend fun awaitNew(pred: (Event) -> Boolean): List<Event> = withTimeout(5_000) {
            while (true) {
                val fresh = timeline().filter { it.seq !in dispatched && pred(it) }
                if (fresh.isNotEmpty()) { fresh.forEach { dispatched.add(it.seq) }; return@withTimeout fresh }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") emptyList()
        }

        suspend fun timeline(): List<Event> = sink.query(EventFilter.ALL, Page(limit = 1000)).events

        suspend fun awaitCount(effectiveType: String, n: Int): Unit = withTimeout(5_000) {
            while (timeline().count { (it.rawType ?: it.type.wire) == effectiveType } < n) delay(10)
        }

        suspend fun printTimeline(title: String) {
            println("\n=== S11 LIVE-TAIL — $title ===")
            for (e in timeline()) {
                val t = (e.rawType ?: e.type.wire).padEnd(16)
                println("seq=${e.seq.toString().padStart(2)}  ${e.severity.name.padEnd(5)}  $t  agent=${e.agentId}  ${e.detail}")
            }
        }

        fun close() = scope.cancel()
    }

    private fun effTypes(events: List<Event>) = events.map { it.rawType ?: it.type.wire }

    @Test
    fun scenarioA_stall_nudge_recovery() = runBlocking {
        val h = Harness()
        try {
            // Sense: a throttle (status=blocked) then silence past T → stall.suspected.
            h.injectThrottle("blocked")
            h.advanceAndSweep(h.nowMs + 60_000) // T elapsed
            h.awaitCount("stall.suspected", 1)
            // Decide+Act: after the first backoff (30s) the Warden nudges.
            h.advanceAndTick(h.nowMs + 30_000)
            h.awaitCount("nudge.sent", 1)
            // Recovery: the agent resumes work → stall.recovered, incident closed.
            h.injectActivity()
            h.advanceAndTick(h.nowMs + 60_000) // even after another backoff window…
            h.awaitCount("stall.recovered", 1)

            h.printTimeline("Scenario A: stall → nudge → recovery")
            val types = effTypes(h.timeline())
            assertEquals(1, types.count { it == "stall.suspected" })
            assertEquals(1, types.count { it == "nudge.sent" }, "exactly one nudge before recovery")
            assertEquals(1, types.count { it == "stall.recovered" })
            assertEquals(0, types.count { it == "stall.escalated" }, "recovery means no escalation")
            assertTrue(types.indexOf("stall.suspected") < types.indexOf("nudge.sent"))
            assertTrue(types.indexOf("nudge.sent") < types.indexOf("stall.recovered"))
            assertEquals(listOf("Bitte mach weiter mit der laufenden Aufgabe."), h.session.nudges)
        } finally {
            h.close()
        }
    }

    @Test
    fun scenarioB_stall_backoff_escalateAfterN() = runBlocking {
        val h = Harness()
        try {
            h.injectThrottle("rejected") // the other throttle status
            h.advanceAndSweep(h.nowMs + 60_000)
            h.awaitCount("stall.suspected", 1)
            // No recovery: drive through the four growing backoffs (30/60/120/240) → 4 nudges…
            h.advanceAndTick(h.nowMs + 30_000); h.awaitCount("nudge.sent", 1)
            h.advanceAndTick(h.nowMs + 60_000); h.awaitCount("nudge.sent", 2)
            h.advanceAndTick(h.nowMs + 120_000); h.awaitCount("nudge.sent", 3)
            h.advanceAndTick(h.nowMs + 240_000); h.awaitCount("nudge.sent", 4)
            // …then one more backoff window with still no activity → escalate to the PO.
            h.advanceAndTick(h.nowMs + 240_000)
            h.awaitCount("stall.escalated", 1)

            h.printTimeline("Scenario B: stall → 4× nudge → escalation")
            val types = effTypes(h.timeline())
            assertEquals(1, types.count { it == "stall.suspected" })
            assertEquals(4, types.count { it == "nudge.sent" }, "N=4 nudges before escalation")
            assertEquals(1, types.count { it == "stall.escalated" })
            assertEquals(0, types.count { it == "stall.recovered" })
            // The escalation is an error event the PO watches, and carries no agent content.
            val esc = h.timeline().single { (it.rawType ?: it.type.wire) == "stall.escalated" }
            assertEquals(Severity.ERROR, esc.severity)
            assertEquals(4, h.session.nudges.size)
        } finally {
            h.close()
        }
    }
}
