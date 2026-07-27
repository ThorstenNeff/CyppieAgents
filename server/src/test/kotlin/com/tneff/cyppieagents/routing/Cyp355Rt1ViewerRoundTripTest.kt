package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.boot.AgentBusyStateTracker
import com.tneff.cyppieagents.boot.AgentTransitionLock
import com.tneff.cyppieagents.boot.HandoffMotor
import com.tneff.cyppieagents.boot.ResumeOutcomeSignal
import com.tneff.cyppieagents.boot.TerminalControlStateTracker
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.connector.InMemorySessionStore
import com.tneff.cyppieagents.model.ResumeOutcome
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalMode
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.pty.PtyManager
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-355 RT-1 (BE-2) — **independent QA E2E: the round-trip VIEWER leg** over the REAL CYP-381 `/ws/terminal`
 * attach-to-live seam. Held back until CYP-381 landed; now wired against `58bca1aa`.
 *
 * The MOTOR (not the test) spawns the interactive `claude --resume <sid>` PTY via [HandoffMotor.requestMode]
 * → [PtyManager.spawnInteractive]; a WS client then [PtyManager.attach]es to it through [terminalSocket] as a
 * viewer (replay scrollback, then live; multi-viewer). Disconnect detaches ONLY the viewer.
 *
 * **Oracle discipline (PO-ratified, CYP-331 honesty trap):** terminal scrollback is client history — a needle
 * survives even a CONTEXT_LOST hand-back — so it is NOT the survival oracle. The **authoritative** survival
 * signal is the backend hand-back outcome: `control.state == MEDIATED` + `ResumeOutcome == RESUMED_WITH_CONTEXT`.
 * The attach-needle here is only a **read-path smoke** (the viewer really sees the live `--resume` PTY). The
 * mandatory **CONTEXT_LOST discriminator** proves the MEDIATED assertion is not vacuous: the same oracle flips.
 */
class Cyp355Rt1ViewerRoundTripTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // fakeTui: announce its args on start (→ the scrollback the attaching viewer replays: proves it attached to
    // the PTY the MOTOR launched with `--resume sid-1`), then echo each stdin line (proves LIVE streaming through
    // the attached viewer). Blocking read keeps it alive past the motor's liveness-settle window.
    private val FAKE_TUI = """
        #!/usr/bin/env bash
        printf 'MOTOR-UP args=%s\n' "${'$'}*"
        while IFS= read -r line; do printf 'GOT:%s\n' "${'$'}line"; done
    """.trimIndent()

    private fun fakeTui(): File =
        Files.createTempFile("cyp355rt1-tui", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }

    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    private class FakeMediated(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private inner class Rig(val tui: File) {
        val worktree: File = Files.createTempDirectory("cyp355rt1-wt").toFile()
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
            idleDeferBoundMs = { 30_000L },
            now = { 1_000L },
            // fakeTui ignores the flags, but embedding `--resume <sid>` lets the viewer's replay PROVE it attached
            // to the very PTY the motor launched for this session (not a fresh bash-fallback spawn).
            interactiveCommand = { sid -> listOf(tui.absolutePath, "--resume", sid ?: "none") },
            livenessSettleMs = 300L,
            resumeOutcomeWaitMs = 1_500L,
        )
        fun stateOf(agentId: String) = terminalControl.snapshot().firstOrNull { it.agentId == agentId }?.state
        suspend fun handOffToTerminal() {
            sessions.register(FakeMediated("backend"))
            val r = motor.requestMode("backend", TerminalMode.TERMINAL, "op")
            assertEquals(TerminalControlState.INTERACTIVE, r.control.state, "precondition: hand-off to INTERACTIVE")
            assertTrue(pty.isLive("backend"), "precondition: the motor owns a live interactive PTY")
        }
    }

    @Test
    fun handoff_viewerAttachesToLiveResumePty_thenHandBackPreservesContext() = testApplication {
        val rig = Rig(fakeTui())

        // 1) MEDIATED → TERMINAL: the MOTOR spawns the live `claude --resume sid-1` PTY (fakeTui), no viewer.
        rig.handOffToTerminal()

        application {
            install(WebSockets)
            routing { terminalSocket({ rig.pty }, knowsAgent = { it == "backend" }, registry = registry()) }
        }
        val client = createClient { install(ClientWebSockets) }

        // 2) A VIEWER connects on /ws/terminal → ATTACHES to the motor's live PTY (read-path smoke):
        //    replay shows the `--resume sid-1` the motor launched; a live keystroke echoes back.
        client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
            val seen = StringBuilder()
            send(Frame.Text(CommJson.encodeToString(
                TerminalClientFrame.serializer(),
                TerminalInput(Base64.getEncoder().encodeToString("PING\n".toByteArray())),
            )))
            withTimeout(15_000) {
                for (frame in incoming) {
                    val f = CommJson.decodeFromString(TerminalServerFrame.serializer(), (frame as Frame.Text).readText())
                    if (f is TerminalOutput) seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                    if (seen.contains("MOTOR-UP") && seen.contains("GOT:PING")) break
                }
            }
            assertTrue(seen.contains("MOTOR-UP args=--resume sid-1"),
                "read-smoke: the viewer attached to the live PTY the MOTOR launched with `--resume sid-1` (scrollback replay)")
            assertTrue(seen.contains("GOT:PING"),
                "read-smoke: the viewer's keystroke reached the one live process and echoed back (live stream)")
        }

        // 3) Viewer disconnected → detach-only: the motor's PTY is STILL live (never torn down by a viewer).
        assertTrue(rig.pty.isLive("backend"), "a viewer disconnect must NOT tear down the motor-owned session")

        // 4) TERMINAL → ORCHESTRATION hand-back with a resume that KEPT its context.
        //    AUTHORITATIVE survival oracle = backend ResumeOutcome + control.state (NOT terminal scrollback).
        rig.spawnMediated = { id, _ ->
            scope.launch { delay(60); rig.resumeSignal.signal("default", id, ResumeOutcome.RESUMED_WITH_CONTEXT) }
            FakeMediated(id)
        }
        val back = rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op")
        assertEquals(TerminalControlState.MEDIATED, back.control.state,
            "context survived the round-trip → MEDIATED (the authoritative survival signal, not scrollback)")
        assertEquals(TerminalControlState.MEDIATED, rig.stateOf("backend"))
    }

    // DISCRIMINATOR (mandatory negative control): the SAME hand-back + SAME oracle, but a resume that HEALED to a
    // fresh session → the oracle MUST FLIP to CONTEXT_LOST. Proves the positive's MEDIATED is not vacuous (a
    // scrollback needle would survive this too — hence the oracle rides ResumeOutcome, never scrollback).
    @Test
    fun handBack_staleResume_contextLost_flipsTheSurvivalOracle() = runBlocking {
        val rig = Rig(fakeTui())
        rig.handOffToTerminal()

        rig.spawnMediated = { id, _ ->
            scope.launch { delay(60); rig.resumeSignal.signal("default", id, ResumeOutcome.CONTEXT_LOST) }
            FakeMediated(id)
        }
        val back = rig.motor.requestMode("backend", TerminalMode.ORCHESTRATION, "op")
        assertEquals(TerminalControlState.CONTEXT_LOST, back.control.state,
            "same oracle, stale resume → FLIPS to CONTEXT_LOST (discriminates the positive's MEDIATED)")
        assertEquals(TerminalControlState.CONTEXT_LOST, rig.stateOf("backend"))
    }
}
