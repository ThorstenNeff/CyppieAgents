package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalServerFrame
import com.tneff.cyppieagents.pty.PtyManager
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-332 — `/ws/terminal` end-to-end: the frame round-trip (client [TerminalInput] → real pty4j PTY →
 * [TerminalOutput] back), the auth gate, the unknown-agent guard, and the single-flight `pty_busy` close.
 * A REAL PTY runs a fake interactive TUI (like [com.tneff.cyppieagents.pty.PtyManagerTest]).
 */
class Cyp332TerminalSocketTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val FAKE_TUI = """
        #!/usr/bin/env bash
        while IFS= read -r line; do printf 'GOT:%s\n' "${'$'}line"; done
    """.trimIndent()

    private fun fakeTui(): File =
        Files.createTempFile("fake-tui-ws", ".sh").toFile().apply { writeText(FAKE_TUI); setExecutable(true) }

    private fun registry() = TokenRegistry(mapOf("tok-backend" to "backend"), operatorToken = "tok-op")

    private fun ApplicationTestBuilder.installTerminal(command: File) {
        val mgr = PtyManager(
            worktreeDirOf = { Files.createTempDirectory("cyp332-ws-wt").toFile() },
            resolveApiKey = { null },
            scope = scope,
            command = listOf(command.absolutePath),
        )
        application {
            install(WebSockets)
            routing { terminalSocket({ mgr }, knowsAgent = { it == "backend" }, registry = registry()) }
        }
    }

    private fun wsClient(b: ApplicationTestBuilder) = b.createClient { install(ClientWebSockets) }
    private fun input(text: String) =
        Frame.Text(CommJson.encodeToString(TerminalClientFrame.serializer(), TerminalInput(Base64.getEncoder().encodeToString(text.toByteArray())) as TerminalClientFrame))
    private fun DefaultClientWebSocketSession.decode(f: Frame): TerminalServerFrame =
        CommJson.decodeFromString(TerminalServerFrame.serializer(), (f as Frame.Text).readText())

    @Test
    fun input_reachesThePty_andOutput_streamsBack_overTheSocket() = testApplication {
        installTerminal(fakeTui())
        val client = wsClient(this)
        client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
            send(input("hello\n"))
            val seen = StringBuilder()
            withTimeout(15_000) {
                for (frame in incoming) {
                    val f = decode(frame)
                    if (f is TerminalOutput) seen.append(String(Base64.getDecoder().decode(f.dataBase64)))
                    if (seen.contains("GOT:hello")) break
                }
            }
            assertTrue(seen.contains("GOT:hello"), "the typed input reached the PTY and its output came back over the socket")
        }
    }

    @Test
    fun noCredential_closes1008_unauthorized() = testApplication {
        installTerminal(fakeTui())
        val client = wsClient(this)
        client.webSocket("/ws/terminal?agentId=backend") { // no token
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "no credential → 1008")
        }
    }

    @Test
    fun unknownAgent_closes1003() = testApplication {
        installTerminal(fakeTui())
        val client = wsClient(this)
        client.webSocket("/ws/terminal?agentId=ghost&token=tok-op") {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, reason?.code, "unknown agent → 1003")
        }
    }

    @Test
    fun secondConcurrentConnect_forSameAgent_closesPtyBusy_singleFlight() = testApplication {
        installTerminal(fakeTui())
        val client = wsClient(this)
        val firstLive = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = scope.launch {
            client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
                send(input("x\n"))
                withTimeout(15_000) {
                    for (frame in incoming) {
                        val f = decode(frame)
                        if (f is TerminalOutput && String(Base64.getDecoder().decode(f.dataBase64)).contains("GOT:x")) break
                    }
                }
                firstLive.complete(Unit) // the first PTY is definitely spawned + live
                release.await()          // hold the connection so the second races a LIVE session
            }
        }
        firstLive.await()
        client.webSocket("/ws/terminal?agentId=backend&token=tok-op") {
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code, "a second live PTY for the agent → 1008")
            assertEquals("pty_busy", reason?.message, "…specifically pty_busy (single-flight §4.1)")
        }
        release.complete(Unit); first.cancel()
    }
}
