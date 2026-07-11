package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalResize
import com.tneff.cyppieagents.model.TerminalServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-348 — the FULL-BOOT terminal journey (QA fidelity leg, deferred from CYP-332/334). Real bytes flow
 * end-to-end through a LIVE pty4j PTY over `/ws/terminal`, and the PTY is spawned by the **real
 * BootOrchestrator→PtyManager wiring** (`e2ePlatform` + the CYP-348 `terminalLaunchCommand` seam = a fake TUI).
 *
 * This closes the exact fidelity caveat I named in `Cyp331TerminalE2eTest`: that one installs the `terminalSocket`
 * route DIRECTLY, not through the full boot (which used to hardcode `claude`). CYP-348's `terminalLaunchCommand`
 * seam now lets the full boot spawn a deterministic fake command → so the whole chain is exercised:
 * `e2ePlatform` boot → `installPlatform` → `/ws/terminal` route → `booted.ptyManager.open` (boot-configured
 * command) → real pty4j PTY → real WS framing (Base64 `TerminalInput`/`Output`/`Resize`/`Exit`). No real claude.
 */
class Cyp348FullBootTerminalJourneyTest {

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

    private fun fakeTui() = Files.createTempFile("cyp348-fboot", ".sh").toFile()
        .apply { writeText(FAKE_TUI); setExecutable(true) }

    private fun input(t: String) =
        Frame.Text(CommJson.encodeToString(TerminalClientFrame.serializer(), TerminalInput(Base64.getEncoder().encodeToString(t.toByteArray()))))
    private fun resize(c: Int, r: Int) =
        Frame.Text(CommJson.encodeToString(TerminalClientFrame.serializer(), TerminalResize(c, r)))

    @Test
    fun realBytes_throughFullBootWiring_overWsTerminal() = runBlocking {
        val projects = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))
        // The CYP-348 seam: inject a deterministic fake TUI as the boot terminal-launch command, so the FULL
        // BootOrchestrator→PtyManager wiring spawns it (no real claude / bash-l dependency).
        e2ePlatform(projects, terminalLaunchCommand = listOf(fakeTui().absolutePath)).use { p ->
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                // CYP-414: /ws/terminal is write-tier (operator-only) since CYP-394 — operator token, faithful to AgentShell.
                client.webSocket("${p.wsBaseUrl}/ws/terminal?agentId=backend&token=${E2ePlatform.OPERATOR_TOKEN}") {
                    // Frame order is preserved server-side: resize applies before the SIZE input.
                    send(input("hello\n")); send(resize(120, 40)); send(input("SIZE\n"))
                    val seen = StringBuilder(); var exit: Int? = null; var quit = false
                    withTimeout(20_000) {
                        for (f in incoming) {
                            if (f !is Frame.Text) continue
                            when (val d = CommJson.decodeFromString(TerminalServerFrame.serializer(), f.readText())) {
                                is TerminalOutput -> {
                                    seen.append(String(Base64.getDecoder().decode(d.dataBase64)))
                                    if (!quit && seen.contains("40 120")) { send(input("QUIT\n")); quit = true }
                                }
                                is TerminalExit -> { exit = d.code; break }
                            }
                        }
                    }
                    assertTrue(seen.contains("GOT:hello"), "input reached the LIVE PTY spawned by the FULL boot wiring: <$seen>")
                    assertTrue(seen.contains("40 120"), "resize took effect through the full boot — child stty size 40 120: <$seen>")
                    assertEquals(42, exit, "clean exit code propagated over /ws/terminal from the full-boot PTY")
                }
            } finally {
                client.close()
            }
        }
    }
}
