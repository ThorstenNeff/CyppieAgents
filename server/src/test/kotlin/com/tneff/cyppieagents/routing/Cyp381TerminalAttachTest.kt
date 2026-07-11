package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-381 (Dev) — the `/ws/terminal` **attach-to-live** branch: when the hand-off motor already owns an
 * interactive PTY (spawned with NO viewer via [PtyManager.spawnInteractive]), a socket must **attach as a viewer**
 * (replay the scrollback, then stream live) — NOT spawn a second process — and a viewer disconnect must **detach
 * only**, leaving the motor's session live. The complementary spawn-bash fallback + auth/unknown guards live in
 * [Cyp332TerminalSocketTest]; a REAL pty4j PTY runs a fake interactive TUI (like [Cyp332TerminalSocketTest]).
 */
class Cyp381TerminalAttachTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    // Announce on start (→ scrollback the attaching viewer replays), then echo each line (proves live streaming).
    private val FAKE_TUI = """
        #!/usr/bin/env bash
        printf 'MOTOR-UP\n'
        while IFS= read -r line; do printf 'GOT:%s\n' "${'$'}line"; done
    """.trimIndent()

    private fun fakeTui(): File =
        Files.createTempFile("cyp381-tui", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }

    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")

    private fun manager(command: File) = PtyManager(
        worktreeDirOf = { Files.createTempDirectory("cyp381-wt").toFile() },
        resolveApiKey = { null },
        scope = scope,
        command = listOf(command.absolutePath),
    )

    @Test
    fun connect_toMotorOwnedLivePty_attachesAsViewer_andDetachLeavesItLive() = testApplication {
        val tui = fakeTui()
        val mgr = manager(tui)
        // The motor owns the interactive PTY (spawnInteractive: no viewer required). The socket must ATTACH, not spawn.
        val exited = CompletableDeferred<Int>()
        mgr.spawnInteractive("backend", 80, 24, listOf(tui.absolutePath)) { code -> exited.complete(code) }
        application {
            install(WebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = registry()) }
        }
        val client = createClient { install(ClientWebSockets) }
        client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
            val seen = StringBuilder()
            withTimeout(15_000) {
                for (frame in incoming) {
                    val f = CommJson.decodeFromString(TerminalServerFrame.serializer(), (frame as Frame.Text).readText())
                    if (f is TerminalOutput) seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                    if (seen.contains("MOTOR-UP")) break
                }
            }
            assertTrue(seen.contains("MOTOR-UP"), "the socket attached to the motor's live PTY and replayed its scrollback")
        }
        // Disconnected: detach-only. The motor's process is STILL live (viewer teardown never touches it).
        assertTrue(mgr.isLive("backend"), "detaching a viewer must NOT tear down the motor-owned session")
        assertFalse(exited.isCompleted, "the motor's process did not exit when the viewer disconnected")
        mgr.close("backend")
    }
}
