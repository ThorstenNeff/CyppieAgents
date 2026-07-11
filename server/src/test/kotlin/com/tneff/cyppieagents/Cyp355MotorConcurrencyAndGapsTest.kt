package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentBusyStateTracker
import com.tneff.cyppieagents.boot.AgentTransitionLock
import com.tneff.cyppieagents.boot.HandoffMotor
import com.tneff.cyppieagents.boot.ResumeOutcomeSignal
import com.tneff.cyppieagents.boot.TerminalControlStateTracker
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.InMemorySessionStore
import com.tneff.cyppieagents.model.ModeChangeOutcome
import com.tneff.cyppieagents.model.ModeChangeRejection
import com.tneff.cyppieagents.model.ResumeOutcome
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalMode
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.pty.PtyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-355 (BE-2) — **independent QA suite (three-eyes E2E leg)** against the hand-off motor, driving the REAL
 * [HandoffMotor] + real [PtyManager] (real pty4j PTYs, fake TUI scripts) + real [AgentTransitionLock] /
 * trackers. Deliberately covers the reject/outcome paths **Dev's `Cyp355HandoffMotorTest` does not**:
 * the concurrency discipline (`IN_TRANSITION` reachability), `ALREADY_IN_TARGET` (RR-1), the motor-driven
 * `CONTEXT_LOST`/`RESUMED_WITH_CONTEXT` classification (RO), and the →ORCHESTRATION total-spawn-fail (NO-3).
 *
 * The RT-1 viewer-visibility leg (`/ws/terminal` attach-to-live) is intentionally held back — Dev's CYP-381
 * attach wiring is not in this bundle yet; the survival tooth here rides the backend [ResumeOutcome], never
 * terminal scrollback (the CYP-331 honesty trap, PO-ratified).
 */
class Cyp355MotorConcurrencyAndGapsTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private fun script(body: String): File =
        Files.createTempFile("cyp355qa-tui", ".sh").toFile()
            .apply { writeText("#!/usr/bin/env bash\n$body\n"); setExecutable(true) }

    private val stayAlive get() = script("sleep 5") // outlives the settle window → CONFIRMED

    private class FakeMediated(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private inner class Rig(val tui: File, idleBoundMs: Long = 30_000L) {
        val worktree: File = Files.createTempDirectory("cyp355qa-wt").toFile()
        val pty = PtyManager(worktreeDirOf = { worktree }, resolveApiKey = { null }, scope = scope)
        val sessions = ConnectorSessions()
        val busy = AgentBusyStateTracker()
        val terminalControl = TerminalControlStateTracker()
        val store = InMemorySessionStore().apply { upsert("default", "backend", "sid-1", now = 1L) }
        val resumeSignal = ResumeOutcomeSignal()
        var spawnMediated: (String, String) -> ConnectorSession = { id, _ -> FakeMediated(id) }
        val motor = HandoffMotor(
            projectId = "default",
            scope = scope,
            transitions = AgentTransitionLock(),
            sessions = sessions,
            ptyManager = { pty },
            spawnMediated = { id, wt -> spawnMediated(id, wt) },
            worktreeOf = { "backend" },
            sessionStore = store,
            busyState = busy,
            terminalControl = terminalControl,
            resumeSignal = resumeSignal,
            idleDeferBoundMs = { idleBoundMs },
            now = { 1_000L },
            interactiveCommand = { listOf(tui.absolutePath) }, // fake TUI, not real `claude`
            livenessSettleMs = 300L,
            resumeOutcomeWaitMs = 1_500L,
        )
        fun stateOf(agentId: String) = terminalControl.snapshot().firstOrNull { it.agentId == agentId }?.state
        /** Bring the agent to a live INTERACTIVE state via a real →TERMINAL hand-off. */
        suspend fun toInteractive() {
            sessions.register(FakeMediated("backend"))
            val r = motor.requestMode("backend", TerminalMode.TERMINAL, "op")
            assertEquals(TerminalControlState.INTERACTIVE, r.control.state, "precondition: hand-off to INTERACTIVE")
        }
    }

    // ── CYP-388 (fast-path LANDED — flipped from the original block-and-queue assertion; the behavior change
    //    owns its test update): a concurrent 2nd request that arrives while #1 HOLDS the transition lock
    //    mid-hand-off gets a PROMPT REJECTED(IN_TRANSITION) via tryLock, instead of blocking on the lock and then
    //    reading the settled INTERACTIVE state (→ the old ALREADY_IN_TARGET). Mutation-proof: reverting the
    //    fast-path to a blocking acquire makes r2 block through #1's ~300ms settle and read INTERACTIVE →
    //    ALREADY_IN_TARGET, reding the IN_TRANSITION assertion. ──
    @Test
    fun concurrentSameAgent_secondGetsPromptInTransition() = runBlocking {
        val rig = Rig(stayAlive)
        rig.sessions.register(FakeMediated("backend"))

        val d1 = scope.async { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op1") }
        // Poll until #1 PROVABLY holds the lock mid-hand-off (state==HANDING_OVER, held through its ~300ms settle)
        // before firing #2 — NOT a fixed delay, else #1 could release first and #2 would read INTERACTIVE →
        // ALREADY_IN_TARGET (spurious red).
        withTimeout(5_000) { while (rig.stateOf("backend") != TerminalControlState.HANDING_OVER) delay(10) }
        val d2 = scope.async { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op2") }
        val r1 = withTimeout(15_000) { d1.await() }
        val r2 = withTimeout(15_000) { d2.await() }

        // d1 confirms INTERACTIVE; d2, arriving while d1 holds the lock, is rejected IN_TRANSITION by the fast-path.
        assertEquals(ModeChangeOutcome.CONFIRMED, r1.outcome)
        assertEquals(TerminalControlState.INTERACTIVE, r1.control.state)
        assertEquals(ModeChangeOutcome.REJECTED, r2.outcome)
        assertEquals(ModeChangeRejection.IN_TRANSITION, r2.reason,
            "the concurrent request hits the held lock → prompt IN_TRANSITION (no block-and-queue into ALREADY_IN_TARGET)")
        rig.pty.close("backend")
    }

    // ── CYP-388 (fast-path LANDED — flipped from the original block-and-queue assertion): a concurrent
    //    request during #1's IDLE-defer returns a PROMPT REJECTED(IN_TRANSITION) via tryLock instead of blocking
    //    on the lock until #1's defer bound. READS RUNTIME (elapsed « bound, under a hard withTimeout) so a
    //    regression to block-and-queue reds — the concurrent call would block through #1's 10s defer and the
    //    withTimeout(2s) would throw. ──
    @Test
    fun concurrentRequest_duringIdleDefer_getsPromptInTransition() = runBlocking {
        val rig = Rig(stayAlive, idleBoundMs = 10_000L) // long defer bound → #1 parks in awaitIdle, holding the lock
        rig.sessions.register(FakeMediated("backend"))
        rig.busy.set("backend", true) // #1 will bounded-defer, HOLDING the lock the whole time

        val d1 = scope.async { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op1") }
        // Poll until #1 PROVABLY holds the lock parked in awaitIdle (state==HANDING_OVER) before firing #2 — NOT a
        // fixed delay. #1 stays HANDING_OVER for the whole 10s defer (busy), so this can't race to a release.
        withTimeout(5_000) { while (rig.stateOf("backend") != TerminalControlState.HANDING_OVER) delay(10) }

        // #2 must return PROMPTLY via the fast-path — not block on the lock until #1's 10s defer bound.
        val t0 = System.nanoTime()
        val r2 = withTimeout(2_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op2") }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertEquals(ModeChangeOutcome.REJECTED, r2.outcome)
        assertEquals(ModeChangeRejection.IN_TRANSITION, r2.reason,
            "the fast-path returns IN_TRANSITION immediately, not block-and-queue")
        assertTrue(elapsedMs < 1_000, "the fast-path returned in ${elapsedMs}ms — not blocked until #1's 10000ms defer bound")

        rig.busy.set("backend", false) // let #1 idle → proceed → INTERACTIVE
        withTimeout(15_000) { d1.await() }
        rig.pty.close("backend")
    }

    // ── RR-1: ALREADY_IN_TARGET no-op reject, both directions, NO transition ─────────────────────────────
    @Test
    fun alreadyInTarget_mediatedToOrchestration_isNoOpReject() = runBlocking {
        val rig = Rig(stayAlive)
        rig.sessions.register(FakeMediated("backend")) // default state == MEDIATED (absent)

        val resp = rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op")

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.ALREADY_IN_TARGET, resp.reason)
        assertNotNull(rig.sessions.session("backend"), "no teardown on a no-op")
        assertFalse(rig.pty.isLive("backend"), "no PTY spawned on a no-op")
    }

    @Test
    fun alreadyInTarget_interactiveToTerminal_isNoOpReject() = runBlocking {
        val rig = Rig(stayAlive)
        rig.toInteractive()

        val resp = rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op")

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.ALREADY_IN_TARGET, resp.reason)
        assertEquals(TerminalControlState.INTERACTIVE, rig.stateOf("backend"), "state unchanged by a no-op")
        rig.pty.close("backend")
    }

    // ── RO (motor-driven): CONTEXT_LOST is CONFIRMED (not REJECTED) with control.state == CONTEXT_LOST ────
    @Test
    fun handBack_contextLost_isConfirmed_withContextLostState() = runBlocking {
        val rig = Rig(stayAlive)
        rig.toInteractive()
        // The resumed mediated session heals to fresh → the connector signals CONTEXT_LOST (armed before respawn).
        rig.spawnMediated = { id, _ ->
            scope.launch { delay(60); rig.resumeSignal.signal("default", id, ResumeOutcome.CONTEXT_LOST) }
            FakeMediated(id)
        }

        val resp = rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op")

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome, "a memory-less hand-back still SUCCEEDS — the mode moved")
        assertEquals(TerminalControlState.CONTEXT_LOST, resp.control.state)
        assertEquals(TerminalControlState.CONTEXT_LOST, rig.stateOf("backend"))
    }

    @Test
    fun handBack_resumedWithContext_isConfirmedMediated() = runBlocking {
        val rig = Rig(stayAlive)
        rig.toInteractive()
        rig.spawnMediated = { id, _ ->
            scope.launch { delay(60); rig.resumeSignal.signal("default", id, ResumeOutcome.RESUMED_WITH_CONTEXT) }
            FakeMediated(id)
        }

        val resp = rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op")

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome)
        assertEquals(TerminalControlState.MEDIATED, resp.control.state, "a resume that kept its context → plain MEDIATED")
    }

    // ── NO-3: →ORCHESTRATION total spawn-fail rolls back to the PRIOR (INTERACTIVE) mode, REJECTED(SPAWN_FAILED) ──
    @Test
    fun handBack_mediatedRespawnThrows_rollsBackToInteractive_rejectSpawnFailed() = runBlocking {
        val rig = Rig(stayAlive)
        rig.toInteractive()
        rig.spawnMediated = { _, _ -> throw RuntimeException("mediated respawn boom") }

        val resp = rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op")

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.SPAWN_FAILED, resp.reason)
        assertEquals(TerminalControlState.INTERACTIVE, resp.control.state,
            "a failed hand-back rolls back to the prior INTERACTIVE mode — never sessionless, never a false MEDIATED")
        assertEquals(TerminalControlState.INTERACTIVE, rig.stateOf("backend"))
        rig.pty.close("backend")
    }
}
