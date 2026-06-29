package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.Personas
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
import kotlin.test.assertTrue

/**
 * CYP-133 — at boot, an agent with no explicit persona gets the ROLE default written to `CLAUDE.md`
 * in its worktree (the connector's CYP-97 auto-discovery placement). Closes the RB1 persona-gap where
 * a persona-less PO did the task itself instead of delegating.
 *
 * **Mutation (teeth):** drop the `?: Personas.forRole(...)` default in BootOrchestrator → personaOf is
 * null → no CLAUDE.md is written → [bootMaterializesRoleDefaultPersonaAsClaudeMd] reddens.
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
    fun bootMaterializesRoleDefaultPersonaAsClaudeMd() {
        val root = Files.createTempDirectory("persona-boot").toFile()
        try {
            val config = PlatformConfig(
                repo = RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(
                    AgentConfig("po", "PO", Role.PO),        // no claudeMd → coordinator default
                    AgentConfig("backend", "BE", Role.WORKER), // no claudeMd → worker default
                ),
            )
            BootOrchestrator(config, secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope).boot()

            val poMd = File(root, "projects/default/po/CLAUDE.md")
            val beMd = File(root, "projects/default/backend/CLAUDE.md")
            assertTrue(poMd.isFile, "PO worktree has a CLAUDE.md (role default written)")
            assertTrue(beMd.isFile, "worker worktree has a CLAUDE.md (role default written)")
            assertEquals(Personas.COORDINATOR, poMd.readText())
            assertEquals(Personas.WORKER, beMd.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitPersonaWinsOverRoleDefault() {
        val root = Files.createTempDirectory("persona-boot2").toFile()
        try {
            val config = PlatformConfig(
                repo = RepoConfig("git@github.com:org/repo.git", "main"),
                agents = listOf(
                    AgentConfig("po", "PO", Role.PO, claudeMd = "# custom PO persona"),
                    AgentConfig("backend", "BE", Role.WORKER),
                ),
            )
            BootOrchestrator(config, secrets(), WorktreeManager(FakeGit(), root), FakeSpawner(), scope).boot()

            // Explicit persona is used verbatim — the role default does NOT override it.
            val po = File(root, "projects/default/po/CLAUDE.md").readText()
            assertEquals("# custom PO persona", po)
            assertTrue(po != Personas.COORDINATOR, "explicit persona must win over the role default")
        } finally {
            root.deleteRecursively()
        }
    }
}
