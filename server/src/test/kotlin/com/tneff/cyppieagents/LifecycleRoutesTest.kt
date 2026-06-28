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
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.AgentRunStateEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Integration coverage of the CYP-73 lifecycle endpoint over the real wired platform (fake git/process). */
class LifecycleRoutesTest {

    private class FakeProcess : AgentProcess {
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
        val gitRoot = Files.createTempDirectory("lifecycle-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> FakeProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    private suspend fun agentsByStatus(tb: ApplicationTestBuilder): Map<String, AgentRunState> {
        val body = tb.client.get("/api/agents").bodyAsText()
        return CommJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Agent.serializer()), body)
            .associate { it.id to it.runState }
    }

    @Test
    fun controls_areOperatorGated_andReflectInStatus() = testApplication {
        application { installPlatform(bootFake()) }

        // Fail-closed: no token → 401; a valid AGENT (non-operator) token → 403 operator_required.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/agents/backend/stop").status)
        val agentTry = client.post("/api/agents/backend/stop") { bearerAuth("tok-backend") }
        assertEquals(HttpStatusCode.Forbidden, agentTry.status)
        assertTrue(agentTry.bodyAsText().contains("operator_required"))

        // Status display is public (no token) and starts RUNNING after boot.
        assertEquals(AgentRunState.RUNNING, agentsByStatus(this)["backend"])

        // Operator stop → 200, and GET /api/agents reflects STOPPED for backend only.
        val stop = client.post("/api/agents/backend/stop") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, stop.status)
        val byStatus = agentsByStatus(this)
        assertEquals(AgentRunState.STOPPED, byStatus["backend"])
        assertEquals(AgentRunState.RUNNING, byStatus["po"], "stopping one agent never affects another")
    }

    @Test
    fun start_onRunning_is409_andUnknownAgent_is404() = testApplication {
        application { installPlatform(bootFake()) }
        // backend is RUNNING after boot → start → 409 already_running.
        val started = client.post("/api/agents/backend/start") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.Conflict, started.status)
        assertTrue(started.bodyAsText().contains("already_running"))
        // Unknown id (operator authorized) → 404 agent_not_found.
        val ghost = client.post("/api/agents/ghost/stop") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.NotFound, ghost.status)
        assertTrue(ghost.bodyAsText().contains("agent_not_found"))
    }

    @Test
    fun lifecycleSocket_failsClosed_withoutToken() = testApplication {
        application { installPlatform(bootFake()) }
        wsClient().webSocket("/ws/lifecycle") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }

    @Test
    fun lifecycleSocket_participantGated_streamsContentFreeSnapshotAndDelta() = testApplication {
        application { installPlatform(bootFake()) }

        // A non-operator AGENT token is accepted (status feed is NOT operator-gated).
        wsClient().webSocket("/ws/lifecycle?token=tok-backend") {
            // Snapshot: one content-free AgentRunStateEvent per agent.
            val snapshot = HashMap<String, AgentRunState>()
            withTimeout(3000) {
                while (snapshot.size < 2) {
                    val frame = incoming.receive() as Frame.Text
                    val raw = frame.readText()
                    // Content-free: the ONLY keys are agentId + status — never event details/bodies.
                    val keys = CommJson.parseToJsonElement(raw).let {
                        (it as kotlinx.serialization.json.JsonObject).keys
                    }
                    assertEquals(setOf("agentId", "runState"), keys, "lifecycle frame must carry only {agentId,runState}")
                    val ev = CommJson.decodeFromString(AgentRunStateEvent.serializer(), raw)
                    snapshot[ev.agentId] = ev.runState
                }
            }
            assertEquals(AgentRunState.RUNNING, snapshot["backend"])
            assertEquals(AgentRunState.RUNNING, snapshot["po"])

            // A control op broadcasts a delta on the same socket.
            client.post("/api/agents/backend/stop") { bearerAuth("tok-op") }
            withTimeout(3000) {
                var seen: AgentRunStateEvent? = null
                while (seen?.runState != AgentRunState.STOPPED) {
                    seen = CommJson.decodeFromString(AgentRunStateEvent.serializer(), (incoming.receive() as Frame.Text).readText())
                }
                assertEquals("backend", seen.agentId)
            }
        }
    }
}
