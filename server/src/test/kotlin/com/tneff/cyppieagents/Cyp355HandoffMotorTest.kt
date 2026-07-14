package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentBusyStateTracker
import com.tneff.cyppieagents.boot.AgentTransitionLock
import com.tneff.cyppieagents.boot.HandoffMotor
import com.tneff.cyppieagents.boot.LifecycleManager
import com.tneff.cyppieagents.boot.ResumeOutcomeSignal
import com.tneff.cyppieagents.boot.TerminalControlStateTracker
import com.tneff.cyppieagents.model.ResumeOutcome
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.InMemorySessionStore
import com.tneff.cyppieagents.model.ModeChangeOutcome
import com.tneff.cyppieagents.model.ModeChangeRejection
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalMode
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.pty.PtyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-355 (BE-2) — the hand-off motor's teeth, driven through the **REAL [PtyManager]** (real pty4j PTYs, fake
 * TUI scripts) + a real [ConnectorSessions] with fake mediated sessions — NOT a mocked PTY. The mutation-critical
 * properties: the never-both-live invariant, the non-optimistic confirm, the IDLE-gate, the env-strip, and the
 * never-`--fork-session` command. The context-survival classification is the CYP-356 [com.tneff.cyppieagents.model.ResumeOutcome]'s
 * job (proven there); here we assert the mode transitions + the sequencing.
 */
class Cyp355HandoffMotorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private fun script(body: String): File =
        Files.createTempFile("cyp355-tui", ".sh").toFile().apply { writeText("#!/usr/bin/env bash\n$body\n"); setExecutable(true) }

    private val stayAlive get() = script("sleep 5") // outlives the settle window → CONFIRMED
    private val immediateExit get() = script("exit 3") // dies inside the settle window → SPAWN_FAILED

    /** A fake mediated session; [onCloseAwait] observes the moment its teardown completes (for the invariant tooth). */
    private class FakeMediated(
        override val agentId: String,
        private val onCloseAwait: () -> Unit = {},
    ) : ConnectorSession {
        override val events: kotlinx.coroutines.flow.Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() = onCloseAwait()
    }

    private class Rig(scope: CoroutineScope, val tui: File, idleBoundMs: Long = 30_000L) {
        val worktree: File = Files.createTempDirectory("cyp355-wt").toFile()
        val pty = PtyManager(
            worktreeDirOf = { worktree },
            resolveApiKey = { null },
            scope = scope,
            baseEnv = mapOf("CLAUDE_CODE_ENTRYPOINT" to "cli", "CLAUDECODE" to "1", "KEEP_ME" to "yes"),
        )
        val sessions = ConnectorSessions()
        val busy = AgentBusyStateTracker()
        val terminalControl = TerminalControlStateTracker()
        val transitions = AgentTransitionLock()
        val resumeSignal = ResumeOutcomeSignal()
        val store = InMemorySessionStore().apply { upsert("default", "backend", "sid-1", now = 1L) }
        var spawnMediated: (String, String) -> ConnectorSession = { id, _ -> FakeMediated(id) }
        val motor = HandoffMotor(
            projectId = "default",
            scope = scope,
            transitions = transitions,
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
            resumeOutcomeWaitMs = 500L,
        )
        fun stateOf(agentId: String) = terminalControl.snapshot().firstOrNull { it.agentId == agentId }?.state
        /** Put [agentId] into INTERACTIVE with a live PTY (the pre-state for a →ORCHESTRATION hand-back).
         *  [onExit] fires when the interactive PROCESS actually terminates — the true "PTY dead" signal (the
         *  `isLive` map is cleared synchronously by both close and closeAndAwait, so it can't tell them apart). */
        fun makeInteractive(agentId: String, onExit: (Int) -> Unit = {}) {
            pty.spawnInteractive(agentId, 80, 24, listOf(tui.absolutePath), onExit)
            terminalControl.set(agentId, TerminalControlState.INTERACTIVE, heldBy = "op", since = 1L)
        }
    }

    // ── Tooth 1: the invariant — the mediated session is torn down BEFORE the PTY exists ──────────────────
    @Test
    fun toTerminal_tearsDownMediated_beforeSpawningPty_neverBothLive() = runBlocking {
        val rig = Rig(scope, stayAlive)
        var ptyLiveAtMediatedClose: Boolean? = null
        // The initial mediated session records whether a PTY is already live when its teardown runs.
        rig.sessions.register(FakeMediated("backend") { ptyLiveAtMediatedClose = rig.pty.isLive("backend") })

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome)
        assertEquals(false, ptyLiveAtMediatedClose, "the PTY must NOT be live yet when the mediated session is torn down (never both live)")
        assertTrue(rig.pty.isLive("backend"), "the interactive PTY is live after CONFIRMED")
        assertNull(rig.sessions.session("backend"), "the mediated session is gone")
        assertEquals(TerminalControlState.INTERACTIVE, rig.stateOf("backend"))
        rig.pty.close("backend")
    }

    // ── Tooth 2a: confirm is non-optimistic — a target that dies in the settle window is REJECTED ──────────
    @Test
    fun toTerminal_spawnDiesImmediately_rejectsSpawnFailed_restoresMediated() = runBlocking {
        val rig = Rig(scope, immediateExit)
        rig.sessions.register(FakeMediated("backend"))
        var restored = false
        rig.spawnMediated = { id, _ -> restored = true; FakeMediated(id) }

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome, "an immediately-dying interactive spawn must NOT confirm")
        assertEquals(ModeChangeRejection.SPAWN_FAILED, resp.reason)
        assertFalse(rig.pty.isLive("backend"), "no lingering PTY after a failed spawn")
        assertTrue(restored, "the mediated session is restored — the agent is never left sessionless")
        assertEquals(TerminalControlState.MEDIATED, rig.stateOf("backend"), "REJECTED leaves the prior mode")
    }

    // ── Tooth 2b: confirm success — a live target confirms INTERACTIVE ─────────────────────────────────────
    @Test
    fun toTerminal_liveTarget_confirmsInteractive() = runBlocking {
        val rig = Rig(scope, stayAlive)
        rig.sessions.register(FakeMediated("backend"))
        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }
        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome)
        assertEquals(TerminalControlState.INTERACTIVE, resp.control.state)
        assertEquals("op", resp.control.heldBy, "the operator holds the interactive session")
        rig.pty.close("backend")
    }

    // ── Tooth 3a: IDLE-gate — a busy agent that never idles is REJECTED(BUSY_TIMEOUT), mediated untouched ──
    @Test
    fun toTerminal_busyPastBound_rejectsBusyTimeout_neverHijacksTurn() = runBlocking {
        val rig = Rig(scope, stayAlive, idleBoundMs = 250L)
        rig.sessions.register(FakeMediated("backend"))
        rig.busy.set("backend", true) // a mediated turn is in flight and never ends

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.BUSY_TIMEOUT, resp.reason)
        assertNotNull(rig.sessions.session("backend"), "the mediated session is NOT torn down — the turn was never hijacked")
        assertFalse(rig.pty.isLive("backend"), "no PTY spawned for a busy agent")
        assertEquals(TerminalControlState.MEDIATED, rig.stateOf("backend"))
    }

    // ── Tooth 3b: IDLE-gate — an agent that goes idle within the bound proceeds to CONFIRMED ───────────────
    @Test
    fun toTerminal_goesIdleWithinBound_proceedsToConfirmed() = runBlocking {
        val rig = Rig(scope, stayAlive, idleBoundMs = 5_000L)
        rig.sessions.register(FakeMediated("backend"))
        rig.busy.set("backend", true)
        val job = scope.launch { delay(150); rig.busy.set("backend", false) } // the turn ends shortly

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }
        job.join()

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome, "once idle, the deferred hand-off proceeds")
        assertEquals(TerminalControlState.INTERACTIVE, rig.stateOf("backend"))
        rig.pty.close("backend")
    }

    // ── Tooth 4: env-strip (AC-b) — a nested CLAUDE_CODE_*/CLAUDECODE never reaches the PTY child ──────────
    @Test
    fun pty_stripsNestedClaudeCodeVars_fromTheChildEnv() = runBlocking {
        val rig = Rig(scope, stayAlive)
        val dump = StringBuilder()
        // A one-shot TUI that dumps its env, spawned through the SAME PtyManager (baseEnv carries the markers).
        rig.pty.open("backend", 80, 24, onOutput = { synchronized(dump) { dump.append(String(it)) } }, onExit = {}, command = listOf(script("env").absolutePath))
        withTimeout(15_000) {
            while (!dump.contains("KEEP_ME=yes")) delay(20) // wait until the env dump has streamed
        }
        rig.pty.close("backend")
        val env = dump.toString()
        assertTrue(env.contains("KEEP_ME=yes"), "an ordinary env var passes through")
        assertFalse(env.contains("CLAUDE_CODE_ENTRYPOINT"), "CLAUDE_CODE_* is stripped from the child env")
        assertFalse(Regex("(^|\\s)CLAUDECODE=").containsMatchIn(env), "CLAUDECODE is stripped from the child env")
    }

    // ── Tooth 5: the resume command NEVER carries --fork-session (CYP-344 (a)) ─────────────────────────────
    @Test
    fun resumeCommand_neverForksSession() {
        val withSid = HandoffMotor.resumeCommandFor("abc")
        assertEquals(listOf("claude", "--resume", "abc"), withSid)
        assertFalse(withSid.contains("--fork-session"), "a hand-off resume must NEVER fork the session (branches the transcript)")
        assertEquals(listOf("claude"), HandoffMotor.resumeCommandFor(null), "no sid → a fresh interactive claude")
    }

    // ── The env-strip predicate is single-sourced and correct ─────────────────────────────────────────────
    @Test
    fun nestedClaudeCodeVar_predicate() {
        assertTrue(PtyManager.isNestedClaudeCodeVar("CLAUDE_CODE_ENTRYPOINT"))
        assertTrue(PtyManager.isNestedClaudeCodeVar("CLAUDECODE"))
        assertFalse(PtyManager.isNestedClaudeCodeVar("PATH"))
        assertFalse(PtyManager.isNestedClaudeCodeVar("ANTHROPIC_API_KEY"))
    }

    // ══ →ORCHESTRATION (hand-back) — the correctness half, symmetric to →TERMINAL ═════════════════════════

    // Tooth B1: the SYMMETRIC invariant — the interactive PROCESS is DEAD before the mediated one spawns (no
    // two-process-same-sid overlap). `isLive` (map) can't prove this — closeAndAwait must have AWAITED the exit.
    @Test
    fun toOrchestration_interactivePtyDead_beforeMediatedSpawns() = runBlocking {
        val rig = Rig(scope, stayAlive)
        val ptyExited = java.util.concurrent.atomic.AtomicBoolean(false)
        rig.makeInteractive("backend") { ptyExited.set(true) }
        var ptyDeadAtMediatedSpawn: Boolean? = null
        rig.spawnMediated = { id, _ -> ptyDeadAtMediatedSpawn = ptyExited.get(); FakeMediated(id) }

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op") }

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome)
        assertEquals(true, ptyDeadAtMediatedSpawn, "the interactive --resume must be DEAD (awaited), not just destroy-requested, before the mediated --resume spawns")
        assertFalse(rig.pty.isLive("backend"), "no PTY after hand-back")
        assertNotNull(rig.sessions.session("backend"), "the mediated session is live after hand-back")
        assertEquals(TerminalControlState.MEDIATED, rig.stateOf("backend"))
    }

    // Tooth B2: rollback — a mediated respawn that throws rolls back to the prior INTERACTIVE mode.
    @Test
    fun toOrchestration_mediatedRespawnThrows_rollsBackToInteractive() = runBlocking {
        val rig = Rig(scope, stayAlive)
        rig.makeInteractive("backend")
        rig.spawnMediated = { _, _ -> throw RuntimeException("mediated spawn failed") }

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op") }

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.SPAWN_FAILED, resp.reason)
        assertEquals(TerminalControlState.INTERACTIVE, rig.stateOf("backend"), "REJECTED rolls back to the prior INTERACTIVE mode")
        assertTrue(rig.pty.isLive("backend"), "the interactive PTY is restored on rollback")
        rig.pty.close("backend")
    }

    // Tooth B3: classification — a stale resume (CONTEXT_LOST signalled during the wait) → control.state=CONTEXT_LOST.
    @Test
    fun toOrchestration_staleResume_classifiesContextLost() = runBlocking {
        val rig = Rig(scope, stayAlive)
        rig.makeInteractive("backend")
        // Faithful to production: the CONTEXT_LOST outcome arrives from the proactive CYP-330 probe AFTER the
        // mediated respawn (spawn → error → heal), i.e. after the motor has armed+subscribed its await — never
        // synchronously. A small delay models that (a synchronous poke would race the SharedFlow subscription).
        rig.spawnMediated = { id, _ -> scope.launch { delay(50); rig.resumeSignal.signal("default", id, ResumeOutcome.CONTEXT_LOST) }; FakeMediated(id) }

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op") }

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome, "the mode DID move to mediated — CONFIRMED")
        assertEquals(TerminalControlState.CONTEXT_LOST, resp.control.state, "a healed stale --resume surfaces as CONTEXT_LOST")
    }

    // Tooth B4: classification — a live resume (no CONTEXT_LOST signal) → control.state=MEDIATED.
    @Test
    fun toOrchestration_liveResume_classifiesMediated() = runBlocking {
        val rig = Rig(scope, stayAlive)
        rig.makeInteractive("backend") // no resumeSignal poke → the await times out → MEDIATED

        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op") }

        assertEquals(ModeChangeOutcome.CONFIRMED, resp.outcome)
        assertEquals(TerminalControlState.MEDIATED, rig.stateOf("backend"), "a resume that kept context is plain MEDIATED")
    }

    // Tooth B5: already-in-target + in-transition rejections.
    @Test
    fun toOrchestration_alreadyMediated_rejectsAlreadyInTarget() = runBlocking {
        val rig = Rig(scope, stayAlive) // no entry → default MEDIATED
        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op") }
        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.ALREADY_IN_TARGET, resp.reason)
    }

    @Test
    fun mode_whileHandingOver_rejectsInTransition() = runBlocking {
        val rig = Rig(scope, stayAlive)
        rig.terminalControl.set("backend", TerminalControlState.HANDING_OVER, heldBy = "op", since = 1L)
        val resp = withTimeout(15_000) { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }
        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.IN_TRANSITION, resp.reason)
    }

    // ── CYP-388: fast-path — a concurrent request WHILE a transition holds the lock (IDLE-deferring up to the
    // bound) is REJECTED(IN_TRANSITION) IMMEDIATELY, not blocked until the bound. Unlike `whileHandingOver`
    // (stale state, lock free), here the lock is genuinely HELD by a parked transition — the case the tryLock
    // fast-path exists for. The test READS RUNTIME (elapsed « bound, under a hard withTimeout) so reverting to
    // the blocking acquire — where the concurrent request would wait out the 10 s defer — reds, not greens. ──
    @Test
    fun concurrentRequest_whileTransitionHoldsLock_rejectsInTransition_immediately() = runBlocking {
        val rig = Rig(scope, stayAlive, idleBoundMs = 10_000L) // long defer bound → the holder parks in awaitIdle
        rig.sessions.register(FakeMediated("backend"))
        rig.busy.set("backend", true) // transition #1 bounded-defers on this busy turn, holding the lock the whole time

        // Transition #1 acquires the lock and parks in the IDLE-defer (state → HANDING_OVER while it holds the lock).
        val first = scope.launch { rig.motor.requestMode("backend", TerminalMode.TERMINAL, "op") }
        withTimeout(5_000) { while (rig.stateOf("backend") != TerminalControlState.HANDING_OVER) delay(10) }

        // Transition #2 for the SAME agent must return IMMEDIATELY — not wait out the 10 s bound the holder is under.
        val t0 = System.nanoTime()
        val resp = withTimeout(2_000) { rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op") }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertEquals(ModeChangeOutcome.REJECTED, resp.outcome)
        assertEquals(ModeChangeRejection.IN_TRANSITION, resp.reason, "a request while a transition holds the lock is IN_TRANSITION")
        assertEquals(TerminalControlState.HANDING_OVER, resp.control.state, "the rejection reports the in-flight transition's state")
        assertTrue(elapsedMs < 1_000, "the fast-path returned in ${elapsedMs}ms — not after the 10000ms defer bound the holder is under")

        rig.busy.set("backend", false) // let #1 go idle, proceed, and release
        first.join()
        rig.pty.close("backend")
    }

    // ── Tooth C: cross-manager stop — restart during INTERACTIVE tears down the PTY, BOUNDED (no deadlock) ──
    // The onTeardown → closeAndAwait runs under the shared transition lock and the restart RE-SPAWNS right
    // after: exactly the CYP-371 deadlock surface. The test READS RUNTIME (elapsed under a hard bound) so a
    // masked hang surfaces as a failure, never as green (the CYP-371 lesson).
    @Test
    fun restartDuringInteractive_tearsDownPty_bounded_noDeadlock() = runBlocking {
        val rig = Rig(scope, stayAlive)
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("backend" to "backend"),
            sessions = rig.sessions,
            ensureWorktree = {},
            spawn = { id, _ -> FakeMediated(id) }, // the respawn-after-teardown
            transitions = rig.transitions, // the SAME shared lock — the deadlock surface
            onTeardown = { rig.pty.closeAndAwait(it) }, // suspend, awaits under the lock
        )
        // CYP-560 — the interactive PTY's onExit completes this: the OBSERVED "the process truly died" signal.
        val ptyExited = CompletableDeferred<Int>()
        rig.makeInteractive("backend", onExit = { code -> ptyExited.complete(code) })
        assertTrue(rig.pty.isLive("backend"))

        // Success = OBSERVED events, never a survived timeout (CYP-560): the restart returns, the REAL interactive-PTY
        // onExit fires (the teardown actually observed the process die), and the mediated session is respawned. The
        // outer withTimeout is a pure DEADLOCK BACKSTOP set ABOVE production's own closeAndAwait belt
        // (CLOSE_AWAIT_TIMEOUT_MS = 5000ms) — it fires ONLY on a real unbounded deadlock (e.g. the shared
        // transition-lock reentrancy the test name references), never on a within-contract slow teardown under load
        // (the old CYP-560 false-RED, where elapsedMs<3000 / withTimeout(4000) asserted BELOW the 5000ms contract).
        withTimeout(8_000) { lifecycle.restart("backend") }

        // Observed-event success (no wall-clock): restart returned within the backstop AND the REAL interactive-PTY
        // onExit fired during the teardown — the process truly died and the teardown observed it. Non-vacuous: restart
        // CAN return with this INCOMPLETE — the belt-degraded path, where closeAndAwait hits its own 5000ms belt
        // WITHOUT the pump joining (onExit never fired) → this reds (an unobserved teardown), never a false-green.
        assertTrue(ptyExited.isCompleted, "the interactive PTY's onExit fired — the teardown OBSERVED the process die (an event, not a survived timeout)")
        assertNotNull(rig.sessions.session("backend"), "the mediated session is respawned")
        assertFalse(rig.pty.isLive("backend"), "restart during INTERACTIVE tears the PTY down (no orphan)")
    }
}
