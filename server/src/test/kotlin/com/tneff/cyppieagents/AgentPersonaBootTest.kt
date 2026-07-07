package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
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
import kotlin.test.assertFalse

/**
 * CYP-310 — the connector's CLAUDE.md AUTO-WRITE is REMOVED (supersedes CYP-133/CYP-97 auto-discovery). At
 * spawn, NO CLAUDE.md is written to the worktree — a new agent starts with an empty (absent) CLAUDE.md, and a
 * pre-existing CLAUDE.md (an external / agent self-edit) is left untouched. It is managed EXCLUSIVELY via the
 * operator-gated `POST /api/agents/{id}/claude-md`.
 *
 * **Mutation (teeth):** re-add `File(cwd,"CLAUDE.md").writeText(persona)` in ClaudeCodeConnector.open() → the
 * spawn materialises/overwrites CLAUDE.md → both tests redden.
 */
class AgentPersonaBootTest {

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

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    @Test
    fun spawn_doesNotAutoWriteClaudeMd_evenWithAPersonaConfig() {
        val root = Files.createTempDirectory("persona-boot").toFile()
        try {
            val config = PlatformConfig(
                repo = RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(
                    AgentConfig("po", "PO", Role.PO),                               // role default (no explicit persona)
                    AgentConfig("backend", "BE", Role.WORKER, claudeMd = "# explicit persona"), // explicit persona
                ),
            )
            BootOrchestrator(config, secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope).boot()

            // CYP-310: NO CLAUDE.md is auto-written — not from the role default, not from an explicit persona config.
            assertFalse(File(root, "projects/default/po/CLAUDE.md").exists(), "PO worktree has NO auto-written CLAUDE.md")
            assertFalse(
                File(root, "projects/default/backend/CLAUDE.md").exists(),
                "worker worktree has NO CLAUDE.md despite an explicit persona config (auto-write is gone)",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun spawn_leavesAPreExistingClaudeMdUntouched() {
        val root = Files.createTempDirectory("persona-boot2").toFile()
        try {
            // An external / agent self-edit exists in the PO worktree BEFORE boot.
            val poMd = File(root, "projects/default/po/CLAUDE.md")
            poMd.parentFile.mkdirs(); poMd.writeText("# EXISTING — an external edit that must survive spawn")
            val config = PlatformConfig(
                repo = RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(AgentConfig("po", "PO", Role.PO, claudeMd = "# role default that must NOT overwrite")),
            )
            BootOrchestrator(config, secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope).boot()

            assertEquals(
                "# EXISTING — an external edit that must survive spawn", poMd.readText(),
                "spawn did NOT overwrite the pre-existing CLAUDE.md (auto-write is gone)",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
