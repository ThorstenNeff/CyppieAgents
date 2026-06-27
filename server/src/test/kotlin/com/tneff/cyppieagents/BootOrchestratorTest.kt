package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.HubConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootOrchestratorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    /** Fake git: records commands and materializes the dirs WorktreeManager probes (for idempotency). */
    private class FakeGit : CommandRunner {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, cwd: File): CommandResult {
            commands += command
            when (command.getOrNull(1)) {
                "clone" -> File(command.last(), ".git").mkdirs()      // repoDir/.git
                "worktree" -> if (command.getOrNull(2) == "add") File(command[3]).mkdirs() // target
            }
            return CommandResult(0, "")
        }
        fun count(vararg prefix: String) = commands.count { it.take(prefix.size) == prefix.toList() }
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        val written = mutableListOf<String>()
        override suspend fun writeLine(line: String) { written.add(line) }
        override fun destroy() {}
    }

    /** Fake spawner; throws when [failCwdContains] appears in the cwd path (spawn-crash simulation). */
    private class FakeSpawner(private val failCwdContains: String? = null) : ProcessSpawner {
        var spawnCount = 0
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            spawnCount++
            if (failCwdContains != null && cwd.path.contains(failCwdContains)) {
                error("simulated spawn crash in $cwd")
            }
            return FakeProcess()
        }
    }

    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        hub = HubConfig(),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("frontend", "FE", Role.WORKER),
            AgentConfig("backend", "BE", Role.WORKER),
        ),
    )

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    private fun gitRoot() = Files.createTempDirectory("boot-test").toFile()

    @Test
    fun bootsAllAgentsWithHubAndSpokeDefault() {
        val git = FakeGit()
        val booted = BootOrchestrator(config(), secrets(), WorktreeManager(git, gitRoot()), FakeSpawner(), scope).boot()

        assertEquals(setOf("po", "frontend", "backend"), booted.bootedAgents.toSet())
        assertTrue(booted.failedAgents.isEmpty())
        assertNotNull(booted.connectorSessions.session("backend"))

        // Hub-and-spoke channels + default ACL derived from config.
        assertEquals(setOf("po-frontend", "po-backend"), booted.state.channels.map { it.id }.toSet())
        assertTrue(booted.state.acl.canWrite("po-backend", "backend"))
        assertFalse(booted.state.acl.canWrite("po-backend", "frontend")) // cross-agent isolation
    }

    @Test
    fun worktreeCreationIsIdempotent() {
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        BootOrchestrator(config(), secrets(), wm, FakeSpawner(), scope).boot()
        BootOrchestrator(config(), secrets(), wm, FakeSpawner(), scope).boot()

        // Despite two boots: cloned once, each worktree added once (dirs already present second time).
        assertEquals(1, git.count("git", "clone"))
        assertEquals(3, git.count("git", "worktree", "add"))
    }

    @Test
    fun perAgentSpawnCrashIsFailClosed() {
        val git = FakeGit()
        val spawner = FakeSpawner(failCwdContains = "backend")
        val booted = BootOrchestrator(config(), secrets(), WorktreeManager(git, gitRoot()), spawner, scope).boot()

        // backend failed → no session, no /ws/agent; po + frontend booted; hub still up.
        assertEquals(listOf("backend"), booted.failedAgents)
        assertEquals(setOf("po", "frontend"), booted.bootedAgents.toSet())
        assertNull(booted.connectorSessions.session("backend"))
        assertNotNull(booted.connectorSessions.session("frontend"))
        assertEquals(setOf("po-frontend", "po-backend"), booted.state.channels.map { it.id }.toSet())
    }

    @Test
    fun configRejectsZeroOrMultiplePOs() {
        assertFailsWith<IllegalArgumentException> {
            PlatformConfig(RepoConfig("u"), agents = listOf(AgentConfig("a", "A", Role.WORKER)))
        }
        assertFailsWith<IllegalArgumentException> {
            PlatformConfig(
                RepoConfig("u"),
                agents = listOf(AgentConfig("a", "A", Role.PO), AgentConfig("b", "B", Role.PO)),
            )
        }
    }

    @Test
    fun secretsFromEnvFailsClosedOnMissingToken() {
        val env = mapOf("HUB_TOKEN_PO" to "x", "OPERATOR_TOKEN" to "op") // frontend token missing
        assertFailsWith<IllegalStateException> {
            Secrets.fromEnv(listOf("po", "frontend")) { env[it] }
        }
        // masked toString never reveals the values
        val s = Secrets(mapOf("tok" to "po"), "operatorsecret", "sk-ant-supersecret")
        assertFalse(s.toString().contains("operatorsecret"))
        assertFalse(s.toString().contains("supersecret"))
    }
}
