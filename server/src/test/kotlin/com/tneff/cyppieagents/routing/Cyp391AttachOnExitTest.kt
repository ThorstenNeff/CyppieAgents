package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalServerFrame
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * CYP-391 (follow-up from CYP-381) — the `/ws/terminal` **attach-path onExit propagation**. When the hand-off
 * motor owns an interactive PTY (spawned with NO viewer via [PtyManager.spawnInteractive]) and that process
 * **dies while an operator is actively viewing it**, the viewer socket must receive a [TerminalExit] and close —
 * mirroring the bash-spawn path's `onExit`, not freezing silently.
 *
 * Before this ticket [PtyManager.attach] carried no exit callback: the spawn path sent `TerminalExit` + closed on
 * process death, the attach path did not, so a dying `claude --resume` under a live viewer left a frozen socket.
 *
 * A REAL pty4j PTY runs a fake interactive TUI (like [Cyp381TerminalAttachTest]/[Cyp332TerminalSocketTest]). The
 * TUI announces itself (→ scrollback the viewer replays), reads ONE line, then exits 7 — so a keystroke from the
 * attached viewer kills the process **from under it**, deterministically. **Mutation-proof:** revert the
 * propagation (drop the attach `onExit` wiring in `TerminalSocket`, or `PtyHandleImpl.fireExit`) → no exit frame
 * ever arrives, the server never closes the socket, and this test reds on the `withTimeout` (the `incoming` loop
 * would block forever waiting for a frame that the spawn path would have sent).
 */
class Cyp391AttachOnExitTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // Announce (→ replayed to the attaching viewer), read ONE line, then die with a distinct code. A keystroke
    // from the viewer completes the `read` and the process exits 7 while the viewer is attached.
    private val FAKE_DYING_TUI = """
        #!/usr/bin/env bash
        printf 'MOTOR-UP\n'
        IFS= read -r line
        exit 7
    """.trimIndent()

    private fun fakeDyingTui(): File =
        Files.createTempFile("cyp391-tui", ".sh").toFile().apply { writeText(FAKE_DYING_TUI); setExecutable(true) }

    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")

    private fun manager(command: File) = PtyManager(
        worktreeDirOf = { Files.createTempDirectory("cyp391-wt").toFile() },
        resolveApiKey = { null },
        scope = scope,
        command = listOf(command.absolutePath),
    )

    private fun input(text: String) = Frame.Text(
        CommJson.encodeToString(
            TerminalClientFrame.serializer(),
            TerminalInput(Base64.getEncoder().encodeToString(text.toByteArray())) as TerminalClientFrame,
        ),
    )

    @Test
    fun motorPtyDies_underActiveViewer_viewerGetsTerminalExit_andSocketCloses() = testApplication {
        val tui = fakeDyingTui()
        val mgr = manager(tui)
        // The motor owns the interactive PTY (no viewer). The socket ATTACHES; it does not spawn a second process.
        val motorExit = CompletableDeferred<Int>()
        mgr.spawnInteractive("backend", 80, 24, listOf(tui.absolutePath)) { code -> motorExit.complete(code) }
        application {
            install(WebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = registry()) }
        }
        val client = createClient { install(ClientWebSockets) }

        client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
            var killed = false
            var exit: TerminalExit? = null
            // One drain loop: it ends ONLY when the server closes the socket (attach-path onExit → outbound.close
            // → NORMAL close). Without the propagation the process still dies, but no TerminalExit is sent and the
            // socket never closes → this withTimeout fires and reds the test.
            withTimeout(20_000) {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val f = CommJson.decodeFromString(TerminalServerFrame.serializer(), frame.readText())
                    when (f) {
                        is TerminalOutput -> {
                            val text = String(Base64.getDecoder().decode(f.dataBase64))
                            // Once attached (scrollback replayed), kill the motor process from under the viewer.
                            if (text.contains("MOTOR-UP") && !killed) { send(input("die\n")); killed = true }
                        }
                        is TerminalExit -> exit = f
                    }
                }
            }
            val received = assertNotNull(exit, "the viewer received a TerminalExit when the motor PTY died under it")
            assertEquals(7, received.code, "the process's real exit code propagated to the viewer")
        }
        // The dead process freed the single-flight slot; the motor's own onExit also fired.
        assertFalse(mgr.isLive("backend"), "the dead motor process freed the PTY slot")
        assertEquals(7, withTimeout(5_000) { motorExit.await() }, "the motor's owner onExit fired too (unchanged)")
    }
}
