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
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BootOrchestratorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    // Uses the shared realistic [FakeGit] (models checked-out branches) — see TestFakeGit.kt.

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

    /** Captures the (cwd, env) handed to each spawn, keyed by the agent (HUB_AGENT_ID). */
    private class CapturingSpawner : ProcessSpawner {
        val byAgent = mutableMapOf<String, Pair<File, Map<String, String>>>()
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            byAgent[env["HUB_AGENT_ID"] ?: "?"] = cwd to env
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
    fun spawnGetsProjectScopedCwdAndProjectResolvedKey() {
        // S12 / CYP-82 end-to-end: the spawn cwd is projects/<projectId>/<agent> and the env carries
        // the PROJECT-resolved API key (not the global team key). Mutations:
        //  - revert WorktreeManager to the flat worktrees/ path → the cwd assertion goes red;
        //  - revert BootOrchestrator to secrets.apiKey → the env key becomes "sk-team", assertion red.
        val git = FakeGit()
        val root = gitRoot()
        val spawner = CapturingSpawner()
        val cfg = config().copy(projectId = "alpha")
        val secrets = Secrets(
            agentTokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
            operatorToken = "tok-op",
            apiKey = "sk-team",
            apiKeysByProject = mapOf("alpha" to "sk-alpha"),
        )
        BootOrchestrator(cfg, secrets, WorktreeManager(git, root, cfg.projectId), spawner, scope).boot()

        val (cwd, env) = spawner.byAgent.getValue("backend")
        assertEquals(File(root, "projects/alpha/backend").absolutePath, cwd.absolutePath, "cwd under projects/<projectId>/<agent>")
        assertEquals("sk-alpha", env["ANTHROPIC_API_KEY"], "spawn env carries the project-resolved key")
    }

    @Test
    fun bootStampsConfigProjectIdOnEvents() = runBlocking {
        // S12 / CYP-83: events carry the active project, single-sourced from config.projectId (not a
        // constant). agent.spawned is recorded through the EventProjector boot builds with config.projectId.
        // Mutation: BootOrchestrator → EventProjector(bander, projectId = "default") → projectId≠"alpha" → red.
        val cfg = config().copy(projectId = "alpha")
        val booted = BootOrchestrator(cfg, secrets(), WorktreeManager(FakeGit(), gitRoot(), cfg.projectId), FakeSpawner(), scope).boot()
        val spawned: Event = withTimeout(5_000) {
            var ev: Event? = null
            while (ev == null) {
                ev = booted.eventSink.query(EventFilter(type = EventType.AGENT_SPAWNED), Page()).events.firstOrNull()
                if (ev == null) delay(20)
            }
            ev
        }
        assertEquals("alpha", spawned.projectId)
    }

    @Test
    fun spawnUsesOperatorStoreKeyOverEnv() {
        // S15 / CYP-96 end-to-end: an operator-set per-project key (persisted in project-config.json)
        // wins over the env key at SPAWN time — proving the connector lazy-resolves through the store.
        // Mutation: revert the connector wiring to secrets.apiKeyFor → env "env-key" leaks, assertion red.
        val dir = Files.createTempDirectory("boot-pcs")
        try {
            val cfgFile = dir.resolve("project-config.json").toFile()
            cfgFile.writeText("""{"alpha":{"apiKey":"store-override"}}""")
            val spawner = CapturingSpawner()
            val cfg = config().copy(projectId = "alpha")
            val secrets = Secrets(
                agentTokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
                operatorToken = "tok-op",
                apiKey = "env-key",
            )
            BootOrchestrator(
                cfg, secrets, WorktreeManager(FakeGit(), gitRoot(), cfg.projectId), spawner, scope,
                projectConfigFile = cfgFile,
            ).boot()

            val (_, env) = spawner.byAgent.getValue("backend")
            assertEquals("store-override", env["ANTHROPIC_API_KEY"], "spawn resolves the operator store override, not env")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun runtimeRegistry_wrapsBootProjectsRealInstances_activeResolvesThem() {
        // CYP-247.1 (L scaffold): the per-project runtime seam is populated with the boot project's runtime,
        // holding the SAME lifecycle instances BootedPlatform exposes directly → zero behavior change. active()
        // resolves them via the live projectId pointer. Mutation: register a runtime holding copies / a wrong
        // projectId, or point the resolver elsewhere → an assertSame/projectId assertion goes red.
        val cfg = config().copy(projectId = "alpha")
        val booted = BootOrchestrator(cfg, secrets(), WorktreeManager(FakeGit(), gitRoot(), cfg.projectId), FakeSpawner(), scope).boot()

        val rt = booted.runtimeRegistry.active()
        assertEquals("alpha", rt.projectId, "active runtime is the boot project")
        assertSame(booted.lifecycle, rt.lifecycle, "seam wraps the REAL lifecycle instance, not a copy")
        assertSame(booted.connectorSessions, rt.connectorSessions)
        assertSame(booted.agentConfigs, rt.agentConfigs)
        assertSame(booted.capabilityRegistry, rt.capabilityRegistry)
        assertSame(booted.providerRegistry, rt.providerRegistry)
        assertSame(booted.agentManagement, rt.agentManagement)
        assertEquals(1, booted.runtimeRegistry.liveCount(), "scaffold: exactly one live runtime")
        assertSame(rt, booted.runtimeRegistry.of("alpha"), "of(bootProject) is the same runtime")
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
