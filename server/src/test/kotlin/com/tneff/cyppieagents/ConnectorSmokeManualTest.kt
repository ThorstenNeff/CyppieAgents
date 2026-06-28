package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.connector.ClaudeCodeConnector
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MANUAL MVP-proof smoke test: one real `claude` turn end-to-end through the live connector.
 * Skipped unless RUN_CONNECTOR_SMOKE=1 (never runs in normal :server:check / CI — it spawns a
 * real process and costs tokens). Run with:
 *
 *   RUN_CONNECTOR_SMOKE=1 ./gradlew :server:test --tests '*ConnectorSmokeManualTest*'
 *
 * Auth: uses ANTHROPIC_API_KEY from env if present (D3 production path); otherwise the CLI falls
 * back to the host's stored Claude-Code credentials. One turn only. Tools empty / permission
 * default — the prompt needs no tools. Evidence is printed masked; no secrets in logs.
 */
class ConnectorSmokeManualTest {

    @Test
    fun realClaudeSingleTurnReachesHubSpoke() {
        assumeTrue("set RUN_CONNECTOR_SMOKE=1 to run the real-claude smoke", System.getenv("RUN_CONNECTOR_SMOKE") == "1")
        runBlocking {
            val worktreesRoot = Files.createTempDirectory("connector-smoke").toFile()
            File(worktreesRoot, "backend").mkdirs() // cwd for the agent session
            val agents = listOf(
                Agent("po", "PO", Role.PO, "po"),
                Agent("backend", "BE", Role.WORKER, "backend"),
            )
            val hub = Hub(HubState.hubAndSpoke(agents), InMemoryMessageStore())
            val registry = SessionRegistry()
            val router = MediationRouter(registry, hub)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val connector = ClaudeCodeConnector(
                spawner = ProcessBuilderSpawner(),
                worktreesRoot = worktreesRoot,
                resolveApiKey = { System.getenv("ANTHROPIC_API_KEY") },
                registry = registry,
                router = router,
                turnQueue = SessionTurnQueue(),
                scope = scope,
                allowedTools = emptyList(),
                permissionMode = "default",
            )
            val session = connector.open("backend")
            val events = CopyOnWriteArrayList<StreamJsonEvent>()
            val sub = scope.launch { session.events.collect { events.add(it) } }

            try {
                // sendTurn holds (single-flight) until this turn's result arrives.
                withTimeout(120_000) {
                    session.sendTurn(UserTurn("Reply with exactly the word: SMOKE-OK and nothing else."))
                }
                delay(200) // let the collector drain the last event

                val posted = hub.channelMessages("po", "po-backend")
                println("=== CONNECTOR SMOKE EVIDENCE (masked) ===")
                println("event types: " + events.map { it::class.simpleName })
                println("session bound: " + registry.agentFor(events.filterIsInstance<com.tneff.cyppieagents.model.SystemEvent>().firstOrNull()?.sessionId ?: "?"))
                println("posted to po-backend: " + posted.map { it.body })
                assertTrue(posted.isNotEmpty(), "a result should have been mediated to the agent's spoke")
            } finally {
                sub.cancel()
                session.close()
                scope.cancel()
                worktreesRoot.deleteRecursively()
            }
        }
    }
}
