package com.tneff.cyppieagents.terminal

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalResize
import com.tneff.cyppieagents.model.TerminalServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket as serverWebSocket

/**
 * CYP-333 live flip (with CYP-348) — end-to-end proof that flipping the Shell view **live** actually attaches to
 * `/ws/terminal`: [WsTerminalSession] connects, streams PTY **stdout** bytes to [WsTerminalSession.incoming], and
 * forwards keystrokes/resize to the PTY **stdin**. CYP-348 makes that PTY a `bash -l` worktree shell by default
 * (server-side; the Tester's full-boot E2E proves the real bash), so this client tooth is command-agnostic: it
 * locks the client half of the contract the flip switches on. Mirrors `EventsWsClientE2eTest`.
 */
class Cyp333LiveShellConnectionTest {

    // --- the flip's core: the live Shell view streams PTY stdout and completes on exit ---

    @Test
    fun liveShell_connects_streamsPtyStdout_andCompletesOnExit() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/terminal") {
                    // The PTY (bash) writes a prompt to stdout, then exits; the socket closes right after.
                    send(Frame.Text(CommJson.encodeToString(TerminalServerFrame.serializer(), TerminalOutput(Base64.Default.encode("bash-5.2\$ ".encodeToByteArray())))))
                    send(Frame.Text(CommJson.encodeToString(TerminalServerFrame.serializer(), TerminalOutput(Base64.Default.encode("ls\n".encodeToByteArray())))))
                    send(Frame.Text(CommJson.encodeToString(TerminalServerFrame.serializer(), TerminalExit(0))))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val session = WsTerminalSession(client, "ws://127.0.0.1:$port", agentId = "backend", token = "op")
                val chunks = withTimeout(10_000) { session.incoming.toList() }
                // The two stdout runs arrive decoded and in order; the exit COMPLETES the flow (teardown signal).
                assertEquals(listOf("bash-5.2\$ ", "ls\n"), chunks.map { it.decodeToString() })
                assertEquals(0, session.lastExitCode, "TerminalExit code is captured")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    // --- interactive both ways: keystrokes + resize reach the PTY stdin as the CYP-332 client frames ---

    @Test
    fun liveShell_forwardsKeystrokesAndResize_toThePtyStdin() = runBlocking {
        val received = mutableListOf<TerminalClientFrame>()
        val server = embeddedServer(Netty, port = 0) {
            install(ServerWebSockets)
            routing {
                serverWebSocket("/ws/terminal") {
                    repeat(2) {
                        val f = incoming.receive()
                        if (f is Frame.Text) received += CommJson.decodeFromString(TerminalClientFrame.serializer(), f.readText())
                    }
                    send(Frame.Text(CommJson.encodeToString(TerminalServerFrame.serializer(), TerminalExit(0))))
                    close()
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO) { install(ClientWebSockets) }
            try {
                val session = WsTerminalSession(client, "ws://127.0.0.1:$port", agentId = "backend", token = "op")
                // Enqueue input + resize (buffered) BEFORE collecting; the sender drains them once connected.
                session.send("ls\n".encodeToByteArray())
                session.resize(cols = 120, rows = 40)
                withTimeout(10_000) { session.incoming.toList() } // drives connect → sender flush → server receives
                assertEquals(2, received.size)
                assertEquals("ls\n", Base64.Default.decode((received[0] as TerminalInput).dataBase64).decodeToString())
                assertEquals(TerminalResize(120, 40), received[1])
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
