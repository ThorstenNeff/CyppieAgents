package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
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
import kotlin.test.assertTrue

/**
 * CYP-247 S4 — the live-box migration money-tooth over the REAL [BootOrchestrator] (git + `claude` faked).
 * (a) An old single-clone layout (`gitRoot/repo` + `projects/default/<agent>` holding the Auftraggeber's
 * CLAUDE.md) is ADOPTED in place on boot — moved to `clones/default`, worktrees repaired, **CLAUDE.md
 * byte-preserved (Rule ①, no wipe)**. (b) A residual project dir not in the registry is PRESERVED + logged
 * by default (D5), and pruned only on the opt-in.
 */
class Cyp247MigrationReconcilerTest {

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
    /** git fake that creates clone/worktree dirs (like the e2e FakeGit) so the reconciler's disk checks are real. */
    private class FakeGit : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult {
            when {
                command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
                command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "")
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val t = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    t?.let { File(it).mkdirs() }
                }
            }
            return CommandResult(0, "")
        }
    }

    private fun boot(gitRoot: File, prune: Boolean = false, registryFile: File? = null) = BootOrchestrator(
        config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
            projectId = "default",
        ),
        secrets = Secrets(mapOf("tok-po" to "po", "tok-backend" to "backend"), operatorToken = "tok-op", apiKey = "key-000000000000"),
        worktrees = WorktreeManager(FakeGit(), gitRoot, "default"),
        spawner = FakeSpawner(),
        scope = scope,
        projectRegistryFile = registryFile,
        pruneResidualProjects = prune,
    ).boot()

    @Test
    fun legacyLayout_bootAdoptsInPlace_bytePreservesClaudeMd_noWipe() {
        val gitRoot = Files.createTempDirectory("cyp247-s4-adopt").toFile()
        // The LEGACY live-box layout: one shared clone + a worktree holding the Auftraggeber's CLAUDE.md.
        File(gitRoot, "repo/.git").mkdirs()
        File(gitRoot, "projects/default/backend").mkdirs()
        val claudeMd = File(gitRoot, "projects/default/backend/CLAUDE.md").apply { writeText("# Backend persona (Auftraggeber)") }

        boot(gitRoot)

        assertFalse(File(gitRoot, "repo").exists(), "the legacy gitRoot/repo was adopted (moved), not left behind")
        assertTrue(File(gitRoot, "clones/default/.git").isDirectory, "the boot clone now lives at clones/default")
        assertTrue(claudeMd.isFile, "the worktree + CLAUDE.md survived the migration")
        assertEquals("# Backend persona (Auftraggeber)", claudeMd.readText(), "Rule ①: the CLAUDE.md is byte-preserved (no wipe)")
        gitRoot.deleteRecursively()
    }

    @Test
    fun bootProject_absentFromLoadedRegistry_preservedEvenWithPruneOptIn() {
        // Rule ① defense-in-depth: a config-drift scenario — a file-backed registry that does NOT list
        // config.projectId ("default"), booted WITH the prune opt-in. The boot project's dir + the
        // Auftraggeber's CLAUDE.md must be HARD-excluded from the residual prune (never treated as orphaned).
        val gitRoot = Files.createTempDirectory("cyp247-s4-drift").toFile()
        File(gitRoot, "projects/default/backend").mkdirs()
        val claudeMd = File(gitRoot, "projects/default/backend/CLAUDE.md").apply { writeText("# Backend persona (Auftraggeber)") }
        val registry = File(gitRoot, "projects.json").apply {
            writeText("""{"activeProjectId":"other","projects":[{"id":"other","name":"Other"}]}""") // NO "default"
        }

        boot(gitRoot, prune = true, registryFile = registry)

        assertTrue(claudeMd.isFile, "the boot project's worktree survived (not pruned as residual)")
        assertEquals("# Backend persona (Auftraggeber)", claudeMd.readText(),
            "Rule ① defense-in-depth: config.projectId is preserved even absent from the registry + prune opt-in")
        gitRoot.deleteRecursively()
    }

    @Test
    fun residualProjectDir_default_preservedAndLogged_optIn_pruned() {
        // (default = preserve) a residual dir for a project NOT in the registry survives boot.
        val g1 = Files.createTempDirectory("cyp247-s4-keep").toFile()
        File(g1, "projects/orphan/worker").mkdirs()
        boot(g1, prune = false)
        assertTrue(File(g1, "projects/orphan").isDirectory, "default: a residual project dir is PRESERVED (logged, not pruned)")
        g1.deleteRecursively()

        // (opt-in = prune) with pruneResidualProjects=true the residual dir is removed.
        val g2 = Files.createTempDirectory("cyp247-s4-prune").toFile()
        File(g2, "projects/orphan/worker").mkdirs()
        boot(g2, prune = true)
        assertFalse(File(g2, "projects/orphan").exists(), "opt-in: the residual project dir is pruned")
        g2.deleteRecursively()
    }
}
