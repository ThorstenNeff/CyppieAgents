package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.ProcessCommandRunner
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.comm.SecretMasker
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.ForbiddenException
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MANUAL S8 full-proof: a real config-driven multi-agent boot against a throwaway repo + real
 * `claude`. Skipped unless RUN_BOOT_PROOF=1 (spawns real processes, costs tokens). Run with:
 *
 *   RUN_BOOT_PROOF=1 BOOT_PROOF_REPO=file:///…/repo.git \
 *     ./gradlew :server:test --tests '*BootProofManualTest*'
 *
 * Auth path = OAuth subscription creds (option b) — NOT the D3 prod key path. Evidence is masked.
 */
class BootProofManualTest {

    @Test
    fun realMultiAgentBoot() {
        assumeTrue("set RUN_BOOT_PROOF=1", System.getenv("RUN_BOOT_PROOF") == "1")
        val repoUrl = System.getenv("BOOT_PROOF_REPO") ?: error("BOOT_PROOF_REPO required")
        runBlocking {

        val gitRoot = Files.createTempDirectory("boot-proof-gitroot").toFile()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val config = PlatformConfig(
            repo = RepoConfig(repoUrl, "main"),
            agents = listOf(
                AgentConfig("po", "Product Owner", Role.PO),
                AgentConfig("frontend", "Frontend", Role.WORKER),
                AgentConfig("backend", "Backend", Role.WORKER),
            ),
        )
        val secrets = Secrets(
            agentTokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
            operatorToken = "tok-op",
            apiKey = System.getenv("ANTHROPIC_API_KEY"), // null → OAuth subscription fallback (option b)
        )

        val booted = BootOrchestrator(
            config, secrets,
            WorktreeManager(ProcessCommandRunner(), gitRoot),
            ProcessBuilderSpawner(),
            scope,
        ).boot()

        try {
            // (1) multiple agents booted, config-driven, each in its OWN worktree.
            assertEquals(setOf("po", "frontend", "backend"), booted.bootedAgents.toSet())
            assertTrue(booted.failedAgents.isEmpty())
            for (id in listOf("po", "frontend", "backend")) {
                assertTrue(File(gitRoot, "worktrees/$id").isDirectory, "worktree for $id")
            }

            // Capture backend's session id (for the bind assertion).
            val backend = booted.connectorSessions.session("backend")!!
            val events = CopyOnWriteArrayList<StreamJsonEvent>()
            val sub = scope.launch { backend.events.collect { events.add(it) } }

            // (2) inject one turn → it holds until the result (single-flight).
            withTimeout(180_000) {
                backend.sendTurn(UserTurn("Reply with exactly the word: BOOT-OK and nothing else."))
            }
            delay(300)

            // (3) session bound: backend's session_id maps to backend.
            val sid = events.filterIsInstance<SystemEvent>().firstNotNullOfOrNull { it.sessionId }
            assertEquals("backend", booted.registry.agentFor(sid ?: "?"))

            // (4) result mediated to the agent's PO spoke.
            val spoke = booted.hub.channelMessages("po", "po-backend")
            assertTrue(spoke.isNotEmpty(), "a result should be mediated to po-backend")

            // (5) ACL enforced: a worker cannot write a foreign channel (fail-closed).
            assertFalse(booted.state.acl.canWrite("po-frontend", "backend"))
            assertFailsWith<ForbiddenException> {
                booted.hub.postAsAgent("backend", "po-frontend", "intrusion")
            }

            // (6) fail-closed auth registry is the production one (env tokens, not dev defaults).
            assertEquals(null, booted.tokenRegistry.agentFor(null))
            assertTrue(booted.tokenRegistry.isOperator("tok-op"))
            assertFalse(booted.tokenRegistry.isOperator("nope"))

            println("=== S8 BOOT PROOF EVIDENCE (masked; auth=OAuth-subscription, not D3) ===")
            println("booted agents : ${booted.bootedAgents}")
            println("worktrees     : ${File(gitRoot, "projects/default").list()?.toList()}")
            println("event types   : ${events.map { it::class.simpleName }.distinct()}")
            println("session bound : sid→${booted.registry.agentFor(sid ?: "?")}")
            println("po-backend    : ${spoke.map { SecretMasker.mask(it.body) }}")
            println("ACL foreign   : canWrite(po-frontend, backend)=${booted.state.acl.canWrite("po-frontend", "backend")}")

            sub.cancel()
        } finally {
            booted.connectorSessions.agentIds().forEach { booted.connectorSessions.remove(it) }
            scope.cancel()
            gitRoot.deleteRecursively()
        }
        }
    }
}
