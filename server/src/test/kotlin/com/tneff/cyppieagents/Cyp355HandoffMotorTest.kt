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
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalMode
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.pty.PtyManager
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
        val store = InMemorySessionStore().apply { upsert("default", "backend", "sid-1", now = 1L) }
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
            resumeSignal = ResumeOutcomeSignal(),
            idleDeferBoundMs = { idleBoundMs },
            now = { 1_000L },
            interactiveCommand = { listOf(tui.absolutePath) }, // fake TUI, not real `claude`
            livenessSettleMs = 300L,
            resumeOutcomeWaitMs = 300L,
        )
        fun stateOf(agentId: String) = terminalControl.snapshot().firstOrNull { it.agentId == agentId }?.state
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
}
