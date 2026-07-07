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
import com.tneff.cyppieagents.model.AgentTokenUsageEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-316 — the `/ws/token-usage` feed over the REAL wired platform (fake git/process). Faithful,
 * end-to-end: a real `ResultEvent.usage` fed onto the agent's stdout flows connector → projector →
 * tracker → socket, and a real lifecycle **restart resets** the agent's value to null.
 */
class TokenUsageSocketTest {

    /** A stdout-feedable fake process (drives real stream-json lines through the connector tap). */
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

    /** Boot the real platform; return it plus the feedable process backing the `backend` agent's FIRST spawn. */
    private fun bootFake(): Pair<BootedPlatform, FeedProcess> {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("token-usage-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val backendProc = FeedProcess()
        val backendClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
        // The spawner gets no agentId, but the cwd is the agent's worktree folder — the `backend` agent's is
        // named "backend". Its FIRST spawn (boot) gets the feedable process; a later respawn (restart) → silent.
        val spawner = ProcessSpawner { _, cwd, _ ->
            if (cwd.name == "backend" && backendClaimed.compareAndSet(false, true)) backendProc else SilentProcess()
        }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot() to backendProc
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    private val initLine = """{"type":"system","subtype":"init","session_id":"sess-1"}"""
    // context = input + cache_read + cache_creation = 12000 + 3000 + 500 = 15500; output_tokens EXCLUDED.
    private val resultLine =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","result":"ok",
           "usage":{"input_tokens":12000,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":9999}}"""

    @Test
    fun socket_failsClosed_withoutToken() = testApplication {
        application { installPlatform(bootFake().first) }
        wsClient().webSocket("/ws/token-usage") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun realResultUsage_pushesContextTokens_thenRestartResetsToNull() = testApplication {
        val (booted, backend) = bootFake()
        application { installPlatform(booted) }

        // Participant (non-operator) token accepted — the title-bar feed is read-tier, content-free.
        wsClient().webSocket("/ws/token-usage?token=tok-backend") {
            // Feed a REAL turn result with usage; it flows connector → projector → tracker → this socket.
            backend.feed(initLine)
            backend.feed(resultLine)

            val pushed = awaitEventFor("backend")
            assertEquals(15500, pushed, "contextTokens = input+cache_read+cache_creation, output EXCLUDED")

            // A real lifecycle restart clears the standing context → the feed resets that agent to null.
            client.post("/api/agents/backend/restart") { bearerAuth("tok-op") }
            assertEquals(null, awaitEventFor("backend"), "restart → fresh context → contextTokens null")
        }
    }

    /** Read frames until an [AgentTokenUsageEvent] for [agentId] arrives; assert it is content-free; return its value. */
    private suspend fun io.ktor.websocket.WebSocketSession.awaitEventFor(agentId: String): Int? = withTimeout(5000) {
        while (true) {
            val raw = (incoming.receive() as Frame.Text).readText()
            val keys = (CommJson.parseToJsonElement(raw) as JsonObject).keys
            assertTrue(keys.all { it == "agentId" || it == "contextTokens" }, "token-usage frame is content-free: $keys")
            val ev = CommJson.decodeFromString(AgentTokenUsageEvent.serializer(), raw)
            if (ev.agentId == agentId) return@withTimeout ev.contextTokens
        }
        @Suppress("UNREACHABLE_CODE") null
    }
}
