package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.SqliteRemoteTokenStore
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-169 / E2.6 — the no-spawn/remote flag. A `remote=true` agent is NOT spawned locally; it is
 * registered into the topology/ACL/token registry + lifecycle (STOPPED), with caps clamped to the REMOTE
 * ceiling from boot (untrusted from birth), and joins over the wire (`/ws/hub`). This closes the CYP-169
 * bug: `launch:""` did NOT skip the local spawn.
 */
class RemoteNoSpawnBootTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    /** Records which agentIds were actually spawned (via the injected HUB_AGENT_ID env). */
    private class RecordingSpawner : ProcessSpawner {
        val spawnedAgents = CopyOnWriteArrayList<String>()
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            env["HUB_AGENT_ID"]?.let { spawnedAgents.add(it) }
            return FakeProcess()
        }
    }

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    private fun boot(spawner: RecordingSpawner): com.tneff.cyppieagents.boot.BootedPlatform {
        val root = Files.createTempDirectory("remote-boot").toFile()
        val config = PlatformConfig(
            repo = RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(
                AgentConfig("po", "PO", Role.PO),                       // local (spawned)
                AgentConfig("backend", "BE", Role.WORKER, remote = true), // remote (NOT spawned)
            ),
        )
        return BootOrchestrator(config, secrets(), WorktreeManager(FakeGit(), root), spawner, scope).boot()
    }

    @Test
    fun remoteAgent_notSpawned_butRegisteredAndReachable() {
        val spawner = RecordingSpawner()
        val booted = boot(spawner)

        // The remote agent is NOT spawned; the local PO is. (Mutation: drop the `agent.remote` branch in
        // the boot spawn loop → backend is spawned → this reds.)
        assertTrue(spawner.spawnedAgents.contains("po"), "the local agent is spawned")
        assertFalse(spawner.spawnedAgents.contains("backend"), "a remote agent must NOT be spawned locally")

        // It IS registered into the lifecycle as STOPPED (awaiting the wire), and the local agent RUNNING.
        assertEquals(AgentRunState.STOPPED, booted.lifecycle.runStateOf("backend"), "remote registered STOPPED")
        assertEquals(AgentRunState.RUNNING, booted.lifecycle.runStateOf("po"))

        // Reachable over the wire: it's in the token registry (token→agentId) and the topology (a spoke).
        assertEquals("backend", booted.tokenRegistry.agentFor("tok-backend"), "remote agent token resolves")
        assertNotNull(booted.state.spokeChannelFor("backend"), "remote agent has a spoke channel (in topology/ACL)")
    }

    @Test
    fun remoteAgent_capsClampedRemoteFromBoot() {
        val booted = boot(RecordingSpawner())

        // Fail-closed: a remote agent is untrusted from birth → REMOTE_CEILING (structuredUsage UNAVAILABLE),
        // NOT stale LOCAL all-AVAILABLE. (Mutation: drop the `agent.remote → REMOTE` trust line → backend
        // resolves LOCAL all-AVAILABLE → structuredUsage AVAILABLE → this reds.)
        assertEquals(
            CapabilityStatus.UNAVAILABLE,
            booted.capabilityRegistry.get("backend")?.structuredUsage,
            "a remote agent's boot caps are clamped to the REMOTE ceiling",
        )
        // The local agent stays all-AVAILABLE (LOCAL trust, byte-unchanged).
        assertEquals(CapabilityStatus.AVAILABLE, booted.capabilityRegistry.get("po")?.structuredUsage)
    }

    /**
     * CYP-690 — boot orphan-purge. A runtime-minted remote-agent token survives in [SqliteRemoteTokenStore] across a
     * restart, but the agent is NOT rehydrated into the roster (its identity was never persisted — CYP-172). Its
     * restored binding would otherwise authenticate a bridge for an agent invisible in the roster — a valid credential
     * outliving its roster entry, unrevocable via the normal agent-removal path. Boot purges it: after the roster is
     * rehydrated, every persisted token whose agentId is not a live participant is revoked + removed.
     *
     * Mutation-proven: delete the CYP-690 purge block in [BootOrchestrator.boot] → the restored binding survives →
     * `agentFor("orphan-token") == "orphan"` → this reds.
     */
    @Test
    fun cyp690_bootPurgesOrphanedRemoteToken_configDeclaredSurvives() {
        val root = Files.createTempDirectory("cyp690").toFile()
        val tokensFile = File(root, "remote-tokens.db")
        // Pre-seed the durable store with an ORPHAN: a runtime-minted token whose agentId ('orphan') is NOT in the
        // config — exactly the post-restart state (the runtime-remote agent's identity was never persisted, so it is
        // not rehydrated into the roster).
        SqliteRemoteTokenStore(tokensFile.toPath()).use { it.put("orphan", "orphan-token") }

        val config = PlatformConfig(
            repo = RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(
                AgentConfig("po", "PO", Role.PO),
                AgentConfig("backend", "BE", Role.WORKER, remote = true), // config-DECLARED remote (durable, in the seed)
            ),
        )
        val booted = BootOrchestrator(
            config, secrets(), WorktreeManager(FakeGit(), root), RecordingSpawner(), scope,
            remoteTokensFile = tokensFile,
        ).boot()

        // ★ the orphaned token NO LONGER authenticates after the boot-purge (revoked).
        assertNull(booted.tokenRegistry.agentFor("orphan-token"), "CYP-690: an orphaned token must not authenticate after the boot-purge")
        // ...and it is REMOVED from the durable store (so it does not resurrect on the next boot's bind loop).
        assertFalse("orphan" in SqliteRemoteTokenStore(tokensFile.toPath()).use { it.all().keys }, "the orphaned token is removed from the durable store")
        // COLLATERAL-FREE BY CONSTRUCTION: a config-declared agent's token lives in the boot seed (secrets.agentTokens),
        // NEVER in remoteTokenStore → the purge cannot touch it → it still authenticates.
        assertEquals("backend", booted.tokenRegistry.agentFor("tok-backend"), "a config-declared remote agent's token still authenticates (collateral-free)")
    }
}
