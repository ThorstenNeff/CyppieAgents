package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.boot.CompactOrchestrator.CompactEvent
import com.tneff.cyppieagents.model.CompactConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-326 — the orchestrator's sequence/timeout/idle-gate/re-arm logic, driven entirely in VIRTUAL TIME
 * (Tester #1, hard requirement): `clock = { testScheduler.currentTime }` and every wait is a `delay` on the
 * injected test scope, so `advanceUntilIdle` runs the whole ~20-min sequence instantly. Send TIMES are
 * captured via the virtual clock, so the 1-min stagger / +10-min gap are asserted exactly. The threshold-watch
 * ([CompactOrchestrator.onPoContext]) is driven directly (start()'s flow collector is trivial plumbing).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompactOrchestratorTest {

    private class Harness(val order: List<String> = listOf("po", "frontend", "backend")) {
        val sends = CopyOnWriteArrayList<Triple<Long, String, String>>() // time, agentId, text
        val events = CopyOnWriteArrayList<CompactEvent>()
        // replay so a completion emitted before the (round-2) collector subscribes is still delivered to it.
        val completions = MutableSharedFlow<String>(replay = 8, extraBufferCapacity = 16)
        var busy: (String) -> Boolean = { false }
        var config = CompactConfig(allowed = true, thresholdTokens = 500_000)
        fun sendsOf(text: String) = sends.filter { it.third == text }.map { it.first to it.second }
        fun done() = events.filterIsInstance<CompactEvent.OrchestrationDone>()
    }

    // scope = the TestScope itself, so advanceUntilIdle drives the run's delays (backgroundScope is NOT advanced
    // by advanceUntilIdle in this coroutines-test version). No start(): drive onPoContext directly.
    private fun TestScope.buildOrch(h: Harness) = CompactOrchestrator(
        scope = this,
        clock = { testScheduler.currentTime },
        poContext = MutableSharedFlow(),
        compactCompletions = h.completions,
        isBusy = { h.busy(it) },
        send = { a, t -> h.sends.add(Triple(testScheduler.currentTime, a, t)); true },
        emit = { h.events.add(it) },
        agentsInOrder = { h.order },
        config = { h.config },
    )

    @Test
    fun fullSequence_poFirst_1minStagger_10minRoundGap_NofN() = runTest {
        val h = Harness(); val orch = buildOrch(h)
        h.completions.tryEmit("po"); h.completions.tryEmit("frontend"); h.completions.tryEmit("backend")
        orch.onPoContext(600_000) // > 500K → fire
        advanceUntilIdle()

        assertEquals(listOf(0L to "po", 60_000L to "frontend", 120_000L to "backend"), h.sendsOf(CompactOrchestrator.PREPARE_TEXT))
        assertEquals(listOf(600_000L to "po", 660_000L to "frontend", 720_000L to "backend"), h.sendsOf(CompactOrchestrator.COMPACT_COMMAND))
        val done = h.done().single()
        assertEquals(3, done.summary.completed); assertEquals(3, done.summary.total)
        assertEquals(emptyList<String>(), done.summary.pendingAgentIds)
    }

    @Test
    fun timeout_neverCompletedAgent_isPending_XofN_neverFaked() = runTest {
        val h = Harness(); val orch = buildOrch(h)
        h.completions.tryEmit("po"); h.completions.tryEmit("frontend") // backend NEVER completes
        orch.onPoContext(700_000)
        advanceUntilIdle()

        val done = h.done().single()
        assertEquals(2, done.summary.completed) // X
        assertEquals(3, done.summary.total)     // N
        assertEquals(listOf("backend"), done.summary.pendingAgentIds) // pending/WARN, not faked
        assertTrue(h.events.filterIsInstance<CompactEvent.Completed>().none { it.agentId == "backend" })
    }

    @Test
    fun idleGate_deferWithBoundedWait_thenSendsWhenIdle() = runTest {
        val h = Harness()
        // backend busy until 900_000 (past its 720_000 /compact slot) — the gate must DEFER, not skip.
        h.busy = { it == "backend" && testScheduler.currentTime < 900_000 }
        val orch = buildOrch(h)
        orch.onPoContext(600_000)
        advanceUntilIdle()

        val backendCompact = h.sendsOf(CompactOrchestrator.COMPACT_COMMAND).singleOrNull { it.second == "backend" }
        assertTrue(backendCompact != null && backendCompact.first >= 900_000L,
            "busy agent's /compact is DEFERRED until it goes idle (~900_000), not skipped and not at 720_000")
    }

    @Test
    fun idleGate_neverIdleInWindow_isPending_notSent() = runTest {
        val h = Harness()
        h.busy = { it == "backend" } // backend busy for the whole window → never gets /compact
        val orch = buildOrch(h)
        orch.onPoContext(600_000)
        advanceUntilIdle()

        assertTrue(h.sendsOf(CompactOrchestrator.COMPACT_COMMAND).none { it.second == "backend" })
        assertTrue("backend" in h.done().single().summary.pendingAgentIds)
    }

    @Test
    fun threshold_fireOncePerUpCrossing_reArmsOnlyAfterDropBelow() = runTest {
        val h = Harness(); val orch = buildOrch(h)
        orch.onPoContext(600_000); advanceUntilIdle() // fire #1
        orch.onPoContext(650_000); advanceUntilIdle() // still above, no drop → NO re-fire
        assertEquals(1, h.done().size)
        orch.onPoContext(100_000) // drop below → re-arm
        orch.onPoContext(700_000); advanceUntilIdle() // cross again → fire #2
        assertEquals(2, h.done().size)
    }

    @Test
    fun notAllowed_doesNotFire_evenAboveThreshold() = runTest {
        val h = Harness().apply { config = CompactConfig(allowed = false, thresholdTokens = 500_000) }
        val orch = buildOrch(h)
        orch.onPoContext(900_000); advanceUntilIdle()
        assertTrue(h.events.isEmpty()) // compact-allowed OFF → fail-closed
    }
}
