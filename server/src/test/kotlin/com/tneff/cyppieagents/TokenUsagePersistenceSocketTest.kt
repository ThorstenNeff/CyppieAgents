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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-325 (defect 2) — the money-tooth for token-value PERSISTENCE over the REAL wired platform: a value fed in
 * boot 1 (real ResultEvent → connector → projector → tracker → the durable store) survives a **server restart**
 * (boot 2 over the SAME gitRoot) and is delivered by snapshot-on-connect WITHOUT any new turn. This is the exact
 * Auftraggeber claim ("nach Refresh/Restart weg" → now durable), proven end-to-end, not a unit stub.
 */
class TokenUsagePersistenceSocketTest {

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

    /** Boot over [gitRoot] with a real [tokenFile] for persistence; returns the platform, the feed process, its scope. */
    private fun boot(gitRoot: File, tokenFile: File): Triple<BootedPlatform, FeedProcess, CoroutineScope> {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val backendProc = FeedProcess()
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val spawner = ProcessSpawner { _, cwd, _ ->
            if (cwd.name == "backend" && claimed.compareAndSet(false, true)) backendProc else SilentProcess()
        }
        val booted = BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope, tokenUsageFile = tokenFile).boot()
        return Triple(booted, backendProc, scope)
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }
    private val initLine = """{"type":"system","subtype":"init","session_id":"sess-1"}"""
    private val resultLine =
        """{"type":"result","subtype":"success","is_error":false,"session_id":"sess-1","result":"ok",
           "usage":{"input_tokens":12000,"cache_read_input_tokens":3000,"cache_creation_input_tokens":500,"output_tokens":9999}}"""

    @Test
    fun tokenValue_survivesServerRestart_snapshotOnConnectDeliversIt() {
        val gitRoot = Files.createTempDirectory("token-persist").toFile()
        val tokenFile = File(gitRoot, ".cyppie/token-usage.json")

        // --- Boot 1: a real turn result persists the value (15500 = input+cache_read+cache_creation). ---
        val (booted1, backend1, scope1) = boot(gitRoot, tokenFile)
        testApplication {
            application { installPlatform(booted1) }
            wsClient().webSocket("/ws/token-usage?token=tok-backend") {
                backend1.feed(initLine)
                backend1.feed(resultLine)
                assertEquals(15500, awaitEventFor("backend"), "the live value is fed and (synchronously) persisted")
            }
        }
        scope1.cancel() // the server process goes away

        // --- Boot 2 (RESTART): SAME gitRoot → the tracker rehydrates from the store; NO new turn is fed. ---
        val (booted2, _, scope2) = boot(gitRoot, tokenFile)
        testApplication {
            application { installPlatform(booted2) }
            wsClient().webSocket("/ws/token-usage?token=tok-backend") {
                assertEquals(15500, awaitEventFor("backend"), "persisted value survives restart, delivered on connect")
            }
        }
        scope2.cancel()
        gitRoot.deleteRecursively()
    }

    private suspend fun io.ktor.websocket.WebSocketSession.awaitEventFor(agentId: String): Int? = withTimeout(5000) {
        while (true) {
            val ev = CommJson.decodeFromString(AgentTokenUsageEvent.serializer(), (incoming.receive() as Frame.Text).readText())
            if (ev.agentId == agentId) return@withTimeout ev.contextTokens
        }
        @Suppress("UNREACHABLE_CODE") null
    }
}
