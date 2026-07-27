package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalResize
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.pty.PtyManager
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.terminalSocket
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-331 (Option D) E2E through-line — the visible milestone: real bytes flow **end-to-end through a LIVE pty4j
 * PTY over a REAL WebSocket socket**. Boots an [embeddedServer] (Netty) on a real ephemeral TCP port with the
 * production [terminalSocket] route + a real [PtyManager] over real pty4j, and drives it with a real Ktor CIO
 * WS client. A fake interactive TUI stands in for `claude` (deterministic + assertable, no API key) — the SAME
 * real-artifact-with-fake-process approach as `RemoteBridgeSubprocessE2eTest`.
 *
 * Proves every byte-path of the CYP-331 criterion at a living PTY:
 *   1. **Input in** → the child echoes `GOT:hello` → an [TerminalOutput] Base64 frame streams back.
 *   2. **Resize takes effect** → after a [TerminalResize] to 120×40, the child's `stty size` reads `40 120`
 *      (rows cols) — the SIGWINCH reached the real PTY, not the initial 80×24.
 *   3. **Clean exit** → the child exits 42 → a [TerminalExit] frame carries the real exit code, socket closes.
 *
 * FIDELITY CAVEAT (honestly named, not waved away): this drives the real terminalSocket route + real PtyManager +
 * real pty4j + real WS framing on a real socket, but the route is installed directly — it does NOT go through the
 * full BootOrchestrator spawn wiring (which hardcodes the `claude` command; no fake-TUI seam yet). That boot-wiring
 * edge is covered by CYP-348's future full-boot E2E (which adds a real launch-mode command param). Boot-auth
 * (1008/1003, knowsAgent, worktree-cwd) is already covered by Cyp332TerminalSocketTest.
 */
class Cyp331TerminalE2eTest {

    // A fake interactive TUI: echoes `GOT:<line>`, prints the child's real window size for `SIZE`, exits 42 for `QUIT`.
    private val FAKE_TUI = """
        #!/usr/bin/env bash
        while IFS= read -r line; do
          case "${'$'}line" in
            SIZE) stty size ;;
            QUIT) exit 42 ;;
            *) printf 'GOT:%s\n' "${'$'}line" ;;
          esac
        done
    """.trimIndent()

    private fun clientFrame(f: TerminalClientFrame) =
        Frame.Text(CommJson.encodeToString(TerminalClientFrame.serializer(), f))
    private fun input(text: String): Frame.Text =
        clientFrame(TerminalInput(Base64.getEncoder().encodeToString(text.toByteArray())))
    private fun resize(cols: Int, rows: Int): Frame.Text = clientFrame(TerminalResize(cols, rows))

    @Test
    fun realBytesFlowEndToEnd_throughLivePty_overRealSocket() = runBlocking {
        val tui = Files.createTempFile("cyp331-tui", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }
        val worktree = Files.createTempDirectory("cyp331-wt").toFile()
        val ptyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val mgr = PtyManager(
            worktreeDirOf = { worktree },
            resolveApiKey = { null },
            scope = ptyScope,
            command = listOf(tui.absolutePath), // fake interactive TUI in place of `claude` — deterministic + assertable
        )
        val registry = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = registry) }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port // real OS-assigned TCP port
        val client = HttpClient(CIO) { install(ClientWebSockets) }

        try {
            client.webSocket("ws://127.0.0.1:$port/ws/terminal?agentId=backend&token=tok-op") {
                // Frame order is preserved server-side (single incoming loop): resize is applied BEFORE the SIZE
                // input, so the child's `stty size` sees the resized window, not the initial 80×24.
                send(input("hello\n"))
                send(resize(120, 40))
                send(input("SIZE\n"))

                val seen = StringBuilder()
                var exitCode: Int? = null
                var quitSent = false
                withTimeout(20_000) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        when (val f = CommJson.decodeFromString(TerminalServerFrame.serializer(), frame.readText())) {
                            is TerminalOutput -> {
                                seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                                // Once the resized size has echoed, ask the child to exit so we can assert the exit code.
                                if (!quitSent && seen.contains("40 120")) { send(input("QUIT\n")); quitSent = true }
                            }
                            is TerminalExit -> { exitCode = f.code; break }
                        }
                    }
                }

                assertTrue(seen.contains("GOT:hello"), "input reached the LIVE PTY and echoed back over the real socket: <$seen>")
                assertTrue(seen.contains("40 120"), "resize took effect — the child's stty size is 40 120 (rows cols), not the initial 24 80: <$seen>")
                assertEquals(42, exitCode, "the PTY's REAL exit code propagated as a clean TerminalExit frame")
            }
        } finally {
            client.close()
            server.stop(200, 200)
            ptyScope.cancel()
            worktree.deleteRecursively()
        }
    }
}
