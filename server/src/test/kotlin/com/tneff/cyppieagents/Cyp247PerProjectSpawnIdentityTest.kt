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
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-247 S1+S1b — the load-bearing SERIAL-SWITCH money-tooth (real-seam: real [BootOrchestrator], real
 * RuntimeRegistry/[com.tneff.cyppieagents.boot.ProjectRuntimeFactory], real [WorktreeManager], real
 * [com.tneff.cyppieagents.connector.ClaudeCodeConnector]; git + the `claude` process faked to observe the
 * spawn identity). Two projects target DIFFERENT repos + keys; a switch makes each agent spawn with ITS OWN
 * identity, and switching back leaves the first intact:
 *
 *  - **S1 clone isolation:** each project clones its own repo into `clones/<pid>` (never a shared clone),
 *    and its agent worktrees live under `projects/<pid>/`.
 *  - **S1b spawn identity (leak 3+5):** the spawn cwd + `ANTHROPIC_API_KEY` come from the SPAWNING project
 *    (threaded projectId), never a boot-frozen `config.projectId`.
 *
 * Mutations that redden this: revert `resolveApiKey`/`worktreesRoot` to `config.projectId`/`active()` →
 * beta's dev spawns with alpha's key/cwd; revert `repoDir` to a shared `gitRoot/repo` → no `clones/beta`.
 */
class Cyp247PerProjectSpawnIdentityTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val repoA = "git@github.com:org/repoA.git"
    private val repoB = "git@github.com:org/repoB.git"

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private data class Spawn(val command: List<String>, val cwd: File, val env: Map<String, String>)

    /** Records every spawn; the dev spawn for a project is the (latest) one whose cwd is under projects/<pid>/. */
    private class CapturingSpawner : ProcessSpawner {
        val spawns = CopyOnWriteArrayList<Spawn>()
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            spawns.add(Spawn(command, cwd, env)); return FakeProcess()
        }
        private fun devFor(pid: String) = spawns.lastOrNull { it.cwd.path.contains("/projects/$pid/dev") }
        fun devKeyFor(pid: String): String? = devFor(pid)?.env?.get("ANTHROPIC_API_KEY")
        fun devCwdFor(pid: String): File? = devFor(pid)?.cwd
    }

    /** Faked git that RECORDS commands and creates clone/worktree dirs (so cwd/clone paths are real on disk). */
    private class RecordingGit : CommandRunner {
        val commands = CopyOnWriteArrayList<List<String>>()
        override fun run(command: List<String>, cwd: File): CommandResult {
            commands.add(command)
            when {
                command.getOrNull(1) == "clone" -> File(command.last(), ".git").mkdirs()
                command.getOrNull(1) == "rev-parse" -> return CommandResult(1, "") // branch absent → -b path
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val target = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    target?.let { File(it).mkdirs() }
                }
            }
            return CommandResult(0, "")
        }
        fun cloneInto(pathFragment: String): List<String>? =
            commands.firstOrNull { it.getOrNull(1) == "clone" && it.last().contains(pathFragment) }
    }

    @Test
    fun serialSwitch_eachProjectSpawnsWithItsOwnKeyCloneAndWorktree() = runBlocking<Unit> {
        val gitRoot = Files.createTempDirectory("cyp247-s1").toFile()
        val git = RecordingGit()
        val spawner = CapturingSpawner()
        val config = PlatformConfig(
            RepoConfig(repoA, "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("dev", "Dev", Role.WORKER)),
            projectId = "alpha",
        )
        val secrets = Secrets(mapOf("tok-po" to "po", "tok-dev" to "dev"), operatorToken = "tok-op", apiKey = "key-ALPHA-0001")
        val booted = BootOrchestrator(config, secrets, WorktreeManager(git, gitRoot, "alpha"), spawner, scope).boot()

        // ── ALPHA (boot project, auto-spawned): dev spawns with alpha's key, cwd + clone under alpha. ──
        assertEquals("key-ALPHA-0001", spawner.devKeyFor("alpha"), "alpha's dev spawns with alpha's API key")
        assertTrue(spawner.devCwdFor("alpha")!!.path.contains("/projects/alpha/dev"), "alpha's dev cwd is under projects/alpha")
        assertNotNull(git.cloneInto("clones/alpha"), "alpha cloned into its OWN clones/alpha")
        assertTrue(git.cloneInto("clones/alpha")!!.contains(repoA), "clones/alpha was cloned from alpha's repo")

        // ── Create + switch to BETA (different repo + key), then start beta's dev. ──
        booted.projectRegistry.create(CreateProjectRequest("beta", "Beta"))
        booted.projectConfig.setRepo("beta", repoB, "main")
        booted.projectConfig.setApiKey("beta", "key-BETA-00001")
        booted.runtimeRegistry.getOrCreate("beta", booted.projectRuntimeFactory)
        booted.state.rescope("beta")
        booted.rehydrateActiveProject()
        booted.runtimeRegistry.active().agentManagement.add(NewAgentSpec("dev", "Dev", Role.WORKER))
        booted.runtimeRegistry.active().lifecycle.start("dev")

        // ── BETA: dev spawns with BETA's key + BETA's clone/worktree — NEVER alpha's (collision-free). ──
        assertEquals("key-BETA-00001", spawner.devKeyFor("beta"), "beta's dev spawns with beta's key (not the boot key — leak 3)")
        assertTrue(spawner.devCwdFor("beta")!!.path.contains("/projects/beta/dev"), "beta's dev cwd is under projects/beta")
        assertNotNull(git.cloneInto("clones/beta"), "beta cloned into its OWN clones/beta (S1 clone isolation)")
        assertTrue(git.cloneInto("clones/beta")!!.contains(repoB), "clones/beta was cloned from beta's DIFFERENT repo")
        assertNotEquals(spawner.devKeyFor("alpha"), spawner.devKeyFor("beta"), "the two same-id 'dev' agents spawn with DIFFERENT keys")

        // ── Switch back to ALPHA → intact: restart dev, still alpha's key + worktree. ──
        booted.state.rescope("alpha")
        booted.rehydrateActiveProject()
        booted.runtimeRegistry.active().lifecycle.restart("dev")
        assertEquals("key-ALPHA-0001", spawner.devKeyFor("alpha"), "after switching back, alpha's dev still spawns with alpha's key")
        assertTrue(spawner.devCwdFor("alpha")!!.path.contains("/projects/alpha/dev"), "alpha's dev worktree intact after the round-trip")

        gitRoot.deleteRecursively()
    }
}
