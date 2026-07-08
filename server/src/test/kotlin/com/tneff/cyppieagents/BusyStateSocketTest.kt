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
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.UserTurn
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-324 — the `/ws/busy-state` feed over the REAL wired platform (fake git/process). Faithful, end-to-end:
 * a real injected turn (`sendTurn` → connector `onTurnStart` → projector → tracker → socket) marks the agent
 * busy, and a real `ResultEvent` on its stdout marks it idle. The PO's three teeth:
 * turn-start→busy, turn-end→idle, and reconnect-mid-turn re-snapshots busy=true.
 */
class BusyStateSocketTest {

    private class FeedProcess : AgentProcess {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override val stdoutLines: Flow<String> = lines.receiveAsFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() { lines.close() }
        suspend fun feed(line: String) = lines.send(line)
    }
    private class SilentProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): Pair<BootedPlatform, FeedProcess> {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("busy-state-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val backendProc = FeedProcess()
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val spawner = ProcessSpawner { _, cwd, _ ->
            if (cwd.name == "backend" && claimed.compareAndSet(false, true)) backendProc else SilentProcess()
        }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot() to backendProc
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }
    private val initLine = """{"type":"system","subtype":"init","session_id":"sess-1"}"""
    private val resultLine =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","result":"ok","usage":{"input_tokens":10}}"""

    @Test
    fun socket_failsClosed_withoutToken() = testApplication {
        application { installPlatform(bootFake().first) }
        wsClient().webSocket("/ws/busy-state") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun realTurn_pushesBusyThenIdle_andReconnectMidTurnResnapshotsBusy() = testApplication {
        val (booted, backend) = bootFake()
        application { installPlatform(booted) }
        val injectScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        backend.feed(initLine) // bind the session
        val session = withTimeout(5000) {
            var s = booted.runtimeRegistry.active().connectorSessions.session("backend")
            while (s == null) { yield(); s = booted.runtimeRegistry.active().connectorSessions.session("backend") }
            s
        }
        // Inject a real turn — onTurnStart marks busy=true; sendTurn then blocks awaiting the result.
        injectScope.launch { session.sendTurn(UserTurn("do work")) }

        wsClient().webSocket("/ws/busy-state?token=tok-backend") {
            assertEquals(true, awaitBusyFor("backend"), "turn.start → busy=true")

            // Reconnect mid-turn: a fresh socket's connect snapshot must re-deliver busy=true (no hanging/lost `*`).
            wsClient().webSocket("/ws/busy-state?token=tok-backend") {
                assertEquals(true, awaitBusyFor("backend"), "reconnect mid-turn → snapshot busy=true")
            }

            backend.feed(resultLine) // the turn's result → idle (and releases the in-flight sendTurn)
            assertEquals(false, awaitBusyFor("backend"), "result → busy=false (idle)")
        }
        injectScope.cancel()
    }

    /** Read frames until an [AgentBusyStateEvent] for [agentId]; assert it is content-free; return its busy flag. */
    private suspend fun io.ktor.websocket.WebSocketSession.awaitBusyFor(agentId: String): Boolean = withTimeout(5000) {
        while (true) {
            val raw = (incoming.receive() as Frame.Text).readText()
            val keys = (CommJson.parseToJsonElement(raw) as JsonObject).keys
            assertTrue(keys.all { it == "agentId" || it == "busy" }, "busy-state frame is content-free: $keys")
            val ev = CommJson.decodeFromString(AgentBusyStateEvent.serializer(), raw)
            if (ev.agentId == agentId) return@withTimeout ev.busy
        }
        @Suppress("UNREACHABLE_CODE") false
    }
}
