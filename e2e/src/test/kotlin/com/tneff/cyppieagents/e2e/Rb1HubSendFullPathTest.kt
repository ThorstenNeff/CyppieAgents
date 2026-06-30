package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-150 ⭐ — the **quota-free FULL-PATH assertion** (the real run-#5 de-risker). With the platform
 * SERVED ([Rb1RealAgentHarness.serveRealAgentPlatform]), a real HTTP `hub_send` to
 * `http://127.0.0.1:<port>/mcp/hub` is **actually routed and POSTED into the hub** — not merely "offered".
 * An HTTP client stands in for the spawned `claude`'s MCP call, so the WHOLE emission chain
 * (HTTP → `/mcp/hub` → `HubMcpTools` → `postAsAgent` → hub) is proven hermetically, **no real claude, 0 quota.**
 *
 * **Mutation:** make `serveRealAgentPlatform` not actually start (serve away) → the endpoint is unreachable
 * (connection refused / no bound port) → this test reddens. Together with [Rb1HubSendOfferedTest] (offered)
 * both emission layers are hermetically pinned, so run #5 closes with high confidence.
 */
class Rb1HubSendFullPathTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }
    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }
    private class FakeRunner : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult = CommandResult(0, "")
    }

    @Test
    fun servedMcpHubSend_actuallyPostsIntoTheHub() {
        val gitRoot = Files.createTempDirectory("rb1-fullpath").toFile()
        // Hermetic boot (fake spawner + fake git): a real BootedPlatform with /mcp/hub + hub + tokens, no claude.
        val booted = Rb1RealAgentHarness.bootRealAgentPlatform(
            gitRoot = gitRoot,
            repoUrl = "file://" + gitRoot.absolutePath,
            scope = scope,
            spawner = FakeSpawner(),
            commandRunner = FakeRunner(),
        )
        val server = Rb1RealAgentHarness.serveRealAgentPlatform(booted, port = 0) // free port
        try {
            // Bounded so a serve-away mutation (server never starts → connectors never resolve) reddens fast.
            val port = runBlocking { kotlinx.coroutines.withTimeout(5_000) { server.engine.resolvedConnectors().first().port } }
            val needle = "FULLPATH-DELEGATE-a17c"

            // Stand in for the spawned claude: an HTTP MCP `hub_send` call to the SERVED endpoint.
            val res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:$port/mcp/hub"))
                    .header("Authorization", "Bearer tok-po")
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_send","arguments":{"channel":"po-backend","text":"$needle"}}}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode(), "the served /mcp/hub answered")

            // ⭐ The proof: the message was ACTUALLY posted into the hub, server-stamped as the bound PO.
            val posted = booted.hub.channelMessages("po", "po-backend")
            assertTrue(posted.any { it.from == "po" && it.body.contains(needle) },
                "a served HTTP hub_send must actually post into the hub (HTTP→/mcp/hub→postAsAgent→hub)")
        } finally {
            server.stop(0, 0)
        }
    }
}
