package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-354 (BE-1) — the `/ws/terminal-state` feed over the REAL wired platform (fake git/process). The mode is
 * driven by the tracker's `set()` (BE-2/CYP-355 drives the real hand-off transitions; here we drive it
 * directly, since BE-1 is the feed, not the motor). Teeth: fail-closed without a token; a `set` pushes the
 * content-free state (+holder/since); a reconnect re-snapshots the current mode; a live delta arrives.
 */
class TerminalControlSocketTest {

    private class SilentProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("terminal-state-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> SilentProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    @Test
    fun socket_failsClosed_withoutToken() = testApplication {
        application { installPlatform(bootFake()) }
        wsClient().webSocket("/ws/terminal-state") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun set_pushesState_contentFree_reconnectResnapshots_andLiveDeltaArrives() = testApplication {
        val booted = bootFake()
        application { installPlatform(booted) }
        val tracker = booted.runtimeRegistry.active().terminalControl

        // Set BEFORE connect → the connect snapshot must deliver it (state + holder + since).
        tracker.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 42L)

        wsClient().webSocket("/ws/terminal-state?token=tok-backend") {
            val snap = awaitStateFor("backend")
            assertEquals(TerminalControlState.INTERACTIVE, snap.state, "connect snapshot delivers the current mode")
            assertEquals("op-1", snap.heldBy, "holder identity is carried")
            assertEquals(42L, snap.since, "since-time is carried")

            // Reconnect mid-hand-off: a fresh socket's snapshot re-delivers the current mode (no lost/hung state).
            wsClient().webSocket("/ws/terminal-state?token=tok-backend") {
                assertEquals(TerminalControlState.INTERACTIVE, awaitStateFor("backend").state, "reconnect re-snapshots INTERACTIVE")
            }

            // The first socket is now subscribed → a live delta (reset → MEDIATED) is delivered reliably.
            tracker.reset("backend")
            val back = awaitStateFor("backend")
            assertEquals(TerminalControlState.MEDIATED, back.state, "reset → live MEDIATED delta")
            assertEquals(null, back.heldBy, "the holder is dropped on reset")
        }
    }

    @Test
    fun lifecycleStop_fansOutTo_terminalControlReset() = kotlinx.coroutines.runBlocking {
        // BE-1 wiring: BootOrchestrator fans the existing onBusyReset lifecycle callback out to the
        // terminal-control tracker (no LifecycleManager touch). A stop ends any hand-off → back to MEDIATED.
        val rt = bootFake().runtimeRegistry.active()
        rt.terminalControl.set("backend", TerminalControlState.INTERACTIVE, heldBy = "op-1", since = 42L)
        rt.lifecycle.stop("backend") // stop → onBusyReset fan-out → terminalControl.reset("backend")
        assertEquals(
            TerminalControlState.MEDIATED,
            rt.terminalControl.snapshot().first { it.agentId == "backend" }.state,
            "a lifecycle stop resets the terminal-control mode to MEDIATED (the BootOrchestrator fan-out)",
        )
    }

    /** Read frames until an [AgentTerminalControlEvent] for [agentId]; assert content-free; return it. */
    private suspend fun io.ktor.websocket.WebSocketSession.awaitStateFor(agentId: String): AgentTerminalControlEvent =
        withTimeout(5000) {
            while (true) {
                val raw = (incoming.receive() as Frame.Text).readText()
                val keys = (CommJson.parseToJsonElement(raw) as JsonObject).keys
                assertTrue(keys.all { it == "agentId" || it == "state" || it == "heldBy" || it == "since" },
                    "terminal-state frame is content-free (no keystrokes/terminal content): $keys")
                val ev = CommJson.decodeFromString(AgentTerminalControlEvent.serializer(), raw)
                if (ev.agentId == agentId) return@withTimeout ev
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
}
