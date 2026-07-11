package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.terminal.WsTerminalSession
import com.tneff.cyppieagents.terminal.WsTtyConnector
import com.jediterm.core.util.TermSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-334 (deferred durchstich, now runnable) — the Shell-Live E2E through the **real client stack**. Where
 * `Cyp348FullBootTerminalJourneyTest` drives the live PTY with a RAW ktor socket + hand-built frames (proving the
 * server/boot/PtyManager wiring), THIS drives the SAME live pty4j PTY through the actual Desktop client classes:
 *
 *   `WsTtyConnector` (the JediTerm bridge — `write()`/`read()`/`resize()`/`waitFor()`, exactly what JediTerm calls)
 *        → `WsTerminalSession` (the `/ws/terminal` client, CYP-332 contract)
 *        → live `/ws/terminal` → real BootOrchestrator→PtyManager → real pty4j PTY.
 *
 * So the CLIENT half is proven end-to-end against a real PTY: keystrokes reach stdin, a viewport resize raises
 * SIGWINCH (child `stty size`), multi-frame stdout decodes through the connector's UTF-8 byte-pipe reader, and the
 * PTY exit completes the read side. A deterministic fake TUI (via the CYP-348 `terminalLaunchCommand` seam) keeps
 * it hermetic — no real `claude`/`bash` hang. Independent of the CYP-333 flip (which only wires this stack into the
 * AgentShell UI — covered by `Cyp333LiveShellConnectionTest` + the render tests).
 */
class Cyp334ClientStackTerminalJourneyTest {

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

    private fun fakeTui() = Files.createTempFile("cyp334-clientstack", ".sh").toFile()
        .apply { writeText(FAKE_TUI); setExecutable(true) }

    @Test
    fun realBytes_throughClientStack_wsTerminalSession_andJediTermConnector() = runBlocking {
        val projects = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))
        e2ePlatform(projects, terminalLaunchCommand = listOf(fakeTui().absolutePath)).use { p ->
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            // The REAL client stack — not a raw socket. Constructing the connector starts its byte-pump, which
            // lazily connects the session to /ws/terminal.
            // CYP-414: /ws/terminal is write-tier (operator-only) since CYP-394 — use the operator token, faithful
            // to AgentShell (which opens the terminal with the operator token). An agent token now 1008s (correct).
            val session = WsTerminalSession(client, p.wsBaseUrl, agentId = "backend", token = E2ePlatform.OPERATOR_TOKEN)
            val connector = WsTtyConnector(session, name = "cyp334-e2e", scope)

            val seen = StringBuilder()
            // JediTerm's emulator-thread pattern: a blocking char read loop over the connector until EOF (-1),
            // which fires when the PTY exits (incoming completes → pipe closes).
            val readThread = Thread {
                val buf = CharArray(4096)
                while (true) {
                    val n = try { connector.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                    if (n < 0) break
                    synchronized(seen) { seen.append(buf, 0, n) }
                }
            }.apply { isDaemon = true; start() }

            try {
                fun snapshot() = synchronized(seen) { seen.toString() }
                // Drive it exactly as JediTerm would: keystrokes via write(), a viewport resize via resize(TermSize).
                connector.write("hello\n")
                connector.resize(TermSize(120, 40)) // columns × rows → child `stty size` reports "40 120"
                connector.write("SIZE\n")
                // Wait until the resize has demonstrably taken effect in the child (proves stdin + SIGWINCH round-trip).
                withTimeout(20_000) {
                    while (!snapshot().contains("40 120")) delay(50)
                }
                connector.write("QUIT\n")
                readThread.join(20_000)

                val out = snapshot()
                assertTrue(out.contains("GOT:hello"), "keystrokes reached the live PTY stdin through the client stack: <$out>")
                assertTrue(out.contains("40 120"), "resize raised SIGWINCH — child stty size 40 120 through the connector: <$out>")
                assertTrue(!readThread.isAlive, "the PTY exit completed the connector read side (EOF)")
                assertEquals(42, session.lastExitCode, "the PTY exit code propagated over /ws/terminal to WsTerminalSession")
            } finally {
                readThread.interrupt()
                connector.close()
                scope.cancel()
                client.close()
            }
        }
    }
}
