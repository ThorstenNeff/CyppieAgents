package com.tneff.cyppieagents.warden

import com.tneff.cyppieagents.scanner.Signal
import com.tneff.cyppieagents.scanner.SignalSink
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CYP-63 stall policy, proven on every axis the spec + PO mandated. Fully deterministic: time is
 * an injected `clock` (a mutable `now`), the throttle arrives as an injected `stall.suspected`
 * [Signal] (no real quota burned), and actions land in a fake [Actuator] / capturing [SignalSink].
 *
 * Backoff 30/60/120/240 s · N = 4 nudges then escalate · one incident per agent · recovery on activity.
 */
class StallPolicyTest {

    private val backoff = listOf(30_000L, 60_000L, 120_000L, 240_000L)

    private class FakeActuator : Actuator {
        val nudges = CopyOnWriteArrayList<Pair<String, String>>()
        val escalations = CopyOnWriteArrayList<Triple<String, String, String>>()
        override suspend fun nudge(agentId: String, text: String) { nudges.add(agentId to text) }
        override suspend fun escalateToPO(agentId: String, reason: String, escalationType: String) {
            escalations.add(Triple(agentId, reason, escalationType))
        }
    }

    private class CapturingSignalSink : SignalSink {
        val emitted = CopyOnWriteArrayList<Signal>()
        override suspend fun emit(signal: Signal) { emitted.add(signal) }
    }

    private class Fixture {
        var now = 0L
        val actuator = FakeActuator()
        val signals = CapturingSignalSink()
        val policy = StallPolicy(clock = { now }, actuator = actuator, signals = signals,
            backoffMs = listOf(30_000L, 60_000L, 120_000L, 240_000L))
    }

    private suspend fun suspect(f: Fixture, agent: String = "backend") =
        f.policy.onSignal(Signal(type = "stall.suspected", agentId = agent, projectId = "default"), f.actuator)

    // ---- Axis: backoff, not Dauerfeuer --------------------------------------------------------
    @Test
    fun nudgesFollowGrowingBackoff_notEveryTick() = runBlocking {
        val f = Fixture()
        suspect(f)
        // Ticking many times before 30s elapses must NOT nudge (this is the anti-Dauerfeuer guard).
        for (t in longArrayOf(0, 5_000, 15_000, 29_999)) { f.now = t; f.policy.tick() }
        assertEquals(0, f.actuator.nudges.size, "no nudge before the first backoff (30s) elapses")

        f.now = 30_000; f.policy.tick(); assertEquals(1, f.actuator.nudges.size) // nudge #1 at +30s
        f.now = 31_000; f.policy.tick(); assertEquals(1, f.actuator.nudges.size) // not yet (+60s needed)
        f.now = 90_000; f.policy.tick(); assertEquals(2, f.actuator.nudges.size) // nudge #2 at +60s
        f.now = 210_000; f.policy.tick(); assertEquals(3, f.actuator.nudges.size) // nudge #3 at +120s
        f.now = 450_000; f.policy.tick(); assertEquals(4, f.actuator.nudges.size) // nudge #4 at +240s
        assertEquals("Bitte mach weiter mit der laufenden Aufgabe.", f.actuator.nudges.first().second)
    }

    // ---- Axis: escalation after N (=4), not at 1 ----------------------------------------------
    @Test
    fun escalatesOnlyAfterNNudges_thenClosesIncident() = runBlocking {
        val f = Fixture()
        suspect(f)
        // Drive through all four backoffs.
        f.now = 30_000; f.policy.tick()
        f.now = 90_000; f.policy.tick()
        f.now = 210_000; f.policy.tick()
        f.now = 450_000; f.policy.tick()
        assertEquals(4, f.actuator.nudges.size)
        assertEquals(0, f.actuator.escalations.size, "must not escalate before N nudges")

        // After the 4th nudge, one more backoff (240s) → escalate, not a 5th nudge.
        f.now = 690_000; f.policy.tick()
        assertEquals(4, f.actuator.nudges.size, "no 5th nudge — escalate instead")
        assertEquals(1, f.actuator.escalations.size)
        val (agent, _, type) = f.actuator.escalations.single()
        assertEquals("backend", agent)
        assertEquals("stall.escalated", type) // policy supplies the type; actuator stays generic

        // Incident closed: further ticks do nothing.
        f.now = 2_000_000; f.policy.tick()
        assertEquals(1, f.actuator.escalations.size)
        assertEquals(4, f.actuator.nudges.size)
    }

    // ---- Axis: one open incident per agent (idempotency) --------------------------------------
    @Test
    fun secondSuspicionWhileOpen_doesNotResetOrParallelizeTheCycle() = runBlocking {
        val f = Fixture()
        suspect(f) // incident opens at t=0 → first nudge due at t=30_000
        f.now = 20_000
        suspect(f) // a second suspicion mid-backoff must be IGNORED (idempotent), not reset the timer
        f.now = 30_000; f.policy.tick()
        // If the 2nd suspicion had reset the incident, the first nudge would only be due at 50_000.
        assertEquals(1, f.actuator.nudges.size, "second suspicion must not delay or duplicate nudging")
    }

    // ---- Axis: recovery on activity -----------------------------------------------------------
    @Test
    fun activityWhileOpen_emitsRecovered_andStopsNudging() = runBlocking {
        val f = Fixture()
        suspect(f)
        f.now = 30_000; f.policy.tick(); assertEquals(1, f.actuator.nudges.size) // one nudge sent

        f.now = 35_000
        f.policy.onActivity("backend") // agent resumed work
        val recovered = f.signals.emitted.single { it.type == "stall.recovered" }
        assertEquals("backend", recovered.agentId)

        // Incident closed → no further nudges or escalation, ever.
        f.now = 1_000_000; f.policy.tick()
        assertEquals(1, f.actuator.nudges.size)
        assertEquals(0, f.actuator.escalations.size)
    }

    @Test
    fun activityWithoutOpenIncident_doesNothing() = runBlocking {
        val f = Fixture()
        f.policy.onActivity("backend") // never suspected
        assertTrue(f.signals.emitted.isEmpty(), "no incident → no recovery signal")
    }

    // ---- Per-agent isolation ------------------------------------------------------------------
    @Test
    fun incidentsArePerAgent() = runBlocking {
        val f = Fixture()
        suspect(f, "backend")  // due at 30_000
        f.now = 10_000
        suspect(f, "frontend") // due at 40_000
        f.now = 39_000; f.policy.tick() // backend due, frontend not yet
        assertEquals(listOf("backend"), f.actuator.nudges.map { it.first }, "only backend's backoff has elapsed")

        // frontend recovers (independently); backend's incident is untouched and keeps nudging.
        f.policy.onActivity("frontend")
        f.now = 99_000; f.policy.tick() // backend's next backoff (60s after the 39_000 nudge) elapsed
        assertEquals(2, f.actuator.nudges.count { it.first == "backend" }, "backend incident lives on its own clock")
        assertTrue(f.actuator.nudges.none { it.first == "frontend" }, "recovered frontend is never nudged")
        assertTrue(f.signals.emitted.any { it.type == "stall.recovered" && it.agentId == "frontend" })
    }
}
