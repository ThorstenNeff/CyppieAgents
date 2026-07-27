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
import com.tneff.cyppieagents.contract.ContractGenerator
import com.tneff.cyppieagents.contract.SchemaWalker
import com.tneff.cyppieagents.contract.oneOfDiscriminatorMapping
import com.tneff.cyppieagents.model.BusyStatus
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StatusFrame
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
 * CYP-840 — the muxed `/ws/status` over the REAL wired platform (fake git/process), plus the export-contract
 * shape. Teeth:
 *  1. [socket_failsClosed_withoutToken] — the mux keeps the 4-feed read-tier gate (fail-closed WS 1008).
 *  2. [realTurn_muxEmitsBusyTagged_andReconnectResnapshots] — a real turn flows through the MUX tagged as a
 *     `busy` [StatusFrame] (snapshot-then-deltas: reconnect mid-turn re-snapshots busy=true; other substreams'
 *     frames interleave and are skipped, proving the merge + the `(type,agentId)` upsert semantics).
 *  3. [statusFrame_exportsAsOneOf_withFourDiscriminators] — the contract shape: `StatusFrame` projects to a
 *     `oneOf` whose discriminator mapping is exactly {lifecycle,tokenUsage,busy,terminal} (the wrap-union).
 *  4. [asyncApi_registersStatusChannel] — the `/ws/status` channel is in the generated asyncapi (the regen DoD).
 */
class Cyp840StatusMuxTest {

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
        val gitRoot = Files.createTempDirectory("status-mux-test").toFile()
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
        // MUT: drop the wsReaderOrNull guard in statusSocket → the socket accepts an anon client → this reds.
        wsClient().webSocket("/ws/status") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun realTurn_muxEmitsBusyTagged_andReconnectResnapshots() = testApplication {
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

        // MUT: mis-wrap the busy feed (e.g. emit LifecycleStatus for a busy event) → the busy frame never arrives → reds.
        wsClient().webSocket("/ws/status?token=tok-backend") {
            assertEquals(true, awaitBusyFor("backend"), "turn.start → busy StatusFrame busy=true (through the mux)")

            // Reconnect mid-turn: the mux's per-substream snapshot must re-deliver busy=true (snapshot-then-deltas).
            wsClient().webSocket("/ws/status?token=tok-backend") {
                assertEquals(true, awaitBusyFor("backend"), "reconnect mid-turn → snapshot busy=true")
            }

            backend.feed(resultLine) // the turn's result → idle (and releases the in-flight sendTurn)
            assertEquals(false, awaitBusyFor("backend"), "result → busy=false (idle) as a live delta")
        }
        injectScope.cancel()
    }

    @Test
    fun statusFrame_exportsAsOneOf_withFourDiscriminators() {
        // MUT: drop a variant / change a @SerialName in StatusFrame → the mapping key-set changes → reds.
        val walker = SchemaWalker()
        walker.schemaFor(StatusFrame.serializer().descriptor) // registers the component
        val schema = walker.components["StatusFrame"] ?: error("StatusFrame not registered as a component")
        val mapping = schema.oneOfDiscriminatorMapping() ?: error("StatusFrame did not project to a oneOf/discriminator")
        assertEquals(setOf("lifecycle", "tokenUsage", "busy", "terminal"), mapping.keys)
    }

    @Test
    fun asyncApi_registersStatusChannel() {
        // MUT: remove the WS_CHANNELS "/ws/status" line → the channel disappears from asyncapi → reds (regen DoD).
        val channels = ContractGenerator.asyncApi()["channels"] as JsonObject
        assertTrue(channels.containsKey("/ws/status"), "asyncapi must declare the /ws/status channel: ${channels.keys}")
    }

    /** Read [StatusFrame]s until a [BusyStatus] for [agentId]; assert its wrapped payload is content-free; return busy. */
    private suspend fun io.ktor.websocket.WebSocketSession.awaitBusyFor(agentId: String): Boolean = withTimeout(5000) {
        while (true) {
            val raw = (incoming.receive() as Frame.Text).readText()
            val frame = CommJson.decodeFromString(StatusFrame.serializer(), raw)
            if (frame is BusyStatus && frame.event.agentId == agentId) {
                val keys = ((CommJson.parseToJsonElement(raw) as JsonObject)["event"] as JsonObject).keys
                assertTrue(keys.all { it == "agentId" || it == "busy" }, "busy payload is content-free: $keys")
                return@withTimeout frame.event.busy
            }
        }
        @Suppress("UNREACHABLE_CODE") false
    }
}
