package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.NewAgentSpec
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-172 Part 2 — a RUNTIME-added remote agent PERSISTS and, across a restart, REHYDRATES fail-closed:
 * re-clamped to the REMOTE ceiling and re-marked remote, with its minted token surviving the CYP-690 purge
 * (it is a live roster participant again — this couples with the Part-1 at-connect cross-check).
 *
 * The all-or-nothing invariant: persisting a remote agent WITHOUT the remote-aware rehydration clamp would let a
 * restart resurrect it with stale LOCAL all-AVAILABLE caps — a caps/trust ESCALATION. So the two halves must land
 * together.
 *
 * MUTATIONS (each reds this tooth):
 *  (a) persist `StoredAgent` with `remote=false` (drop `remote = spec.remote` at AgentManagement.add) → the row
 *      rehydrates as LOCAL → NOT re-clamped → `structuredUsage` != UNAVAILABLE.
 *  (b) skip the rehydration clamp block (BootOrchestrator) → caps null after restart / not marked remote.
 */
class Cyp172Part2RemotePersistRehydrateTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class RecordingSpawner : ProcessSpawner {
        val spawnedAgents = CopyOnWriteArrayList<String>()
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            env["HUB_AGENT_ID"]?.let { spawnedAgents.add(it) }
            return FakeProcess()
        }
    }

    private fun secrets() = Secrets(agentTokens = mapOf("tok-po" to "po"), operatorToken = "tok-op", apiKey = null)

    // Only a LOCAL config agent; the remote is runtime-ADDED (the CYP-172 Part-2 vector, not config-declared).
    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
        agents = listOf(AgentConfig("po", "PO", Role.PO)),
    )

    private fun boot(root: File, paFile: File, tokFile: File) = BootOrchestrator(
        config(), secrets(), WorktreeManager(FakeGit(), root), RecordingSpawner(), scope,
        projectAgentFile = paFile, remoteTokensFile = tokFile,
    ).boot()

    @Test
    fun runtimeRemoteAgent_survivesRestart_rehydratedRemoteClamped_tokenNotPurged() {
        val root = Files.createTempDirectory("cyp172p2").toFile()
        val paFile = File(root, "project-agents.json")
        val tokFile = File(root, "remote-tokens.db")

        // Boot #1: runtime-ADD a remote agent → persists to the durable store (remote=true) + mints a token.
        val booted1 = boot(root, paFile, tokFile)
        val created = booted1.agentManagement.add(NewAgentSpec("byoa", "BYOA", Role.WORKER, remote = true))
        val token = assertNotNull(created.token, "a remote add mints a token")
        // A LOCAL runtime agent too — the rehydration clamp/mark must be SELECTIVE (not over-clamp locals).
        booted1.agentManagement.add(NewAgentSpec("frontend", "FE", Role.WORKER))

        // Boot #2: a RESTART over the SAME durable stores → rehydrateActiveProject runs.
        val booted2 = boot(root, paFile, tokFile)

        // ① rehydrated back into the roster.
        assertNotNull(booted2.state.agent("byoa"), "the runtime remote agent is rehydrated into the roster")
        // ② REMOTE-clamped, fail-closed: structuredUsage UNAVAILABLE, NOT stale LOCAL all-AVAILABLE.
        assertEquals(
            CapabilityStatus.UNAVAILABLE,
            booted2.capabilityRegistry.get("byoa")?.structuredUsage,
            "CYP-172 Part 2: a rehydrated remote agent is re-clamped to the REMOTE ceiling",
        )
        // ③ marked remote → no local worktree (the worktreePath / CLAUDE.md agent_not_local surfaces restored).
        assertNull(booted2.agentManagement.detail("byoa").worktreePath, "a rehydrated remote agent has no local worktree")
        // ④ its minted token SURVIVES the CYP-690 purge — it is a live roster participant again.
        assertEquals("byoa", booted2.tokenRegistry.agentFor(token), "the rehydrated remote agent's token still authenticates")
        // ⑤ SELECTIVE: the local rehydrated agent is NOT over-marked remote (keeps a real worktree path).
        assertNotNull(booted2.agentManagement.detail("frontend").worktreePath, "a local rehydrated agent keeps its worktree")
    }
}
