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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-247 S2 — repoint as re-provision (D4) with the §2d work-guard, real-seam (real BootOrchestrator +
 * WorktreeManager + ClaudeCodeConnector; git + the `claude` process faked to observe the effect). A live
 * project's repo is changed; the NEXT agent (re)start tears the old clone down and re-clones from the NEW
 * repo — UNLESS the guard finds uncommitted/unpushed `agent/<name>` work (then it BLOCKS, no silent loss),
 * which the D5 discard opt-in overrides.
 *
 * Mutation that reddens the block: remove the guard (always tear down) → the unpushed case re-provisions
 * anyway → the "old clone preserved / still pending" assertions redden (silent loss).
 */
class Cyp247RepoReprovisionTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val repoA = "git@github.com:org/repoA.git"
    private val repoB = "git@github.com:org/repoB.git"

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class CapturingSpawner : ProcessSpawner {
        val cwds = CopyOnWriteArrayList<File>()
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess {
            cwds.add(cwd); return FakeProcess()
        }
    }

    /** Faked git that RECORDS commands + creates clone/worktree dirs; [dirty]/[unpushed] drive the §2d guard. */
    private class RecordingGit(private val dirty: Boolean = false, private val unpushed: Boolean = false) : CommandRunner {
        val commands = CopyOnWriteArrayList<List<String>>()
        override fun run(command: List<String>, cwd: File): CommandResult {
            commands.add(command)
            return when {
                command.getOrNull(1) == "clone" -> { File(command.last(), ".git").mkdirs(); CommandResult(0, "") }
                command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                    val target = if (command.getOrNull(3) == "-b") command.getOrNull(5) else command.getOrNull(3)
                    target?.let { File(it).mkdirs() }; CommandResult(0, "")
                }
                command.getOrNull(1) == "status" -> CommandResult(0, if (dirty) " M src/x.kt" else "")
                command.getOrNull(1) == "log" && command.contains("--not") ->
                    CommandResult(0, if (unpushed) "abc123 local wip" else "") // §2d unpushed probe
                command.getOrNull(1) == "rev-parse" -> CommandResult(1, "") // branch absent → -b path
                else -> CommandResult(0, "")
            }
        }
        /** count of `git clone <url> … <path>` whose target dir contains [pathFragment]. */
        fun clonesFrom(url: String, pathFragment: String) =
            commands.count { it.getOrNull(1) == "clone" && it.contains(url) && it.last().contains(pathFragment) }
    }

    private fun boot(git: RecordingGit, spawner: CapturingSpawner): com.tneff.cyppieagents.boot.BootedPlatform {
        val config = PlatformConfig(
            RepoConfig(repoA, "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("dev", "Dev", Role.WORKER)),
            projectId = "alpha",
        )
        val secrets = Secrets(mapOf("tok-po" to "po", "tok-dev" to "dev"), operatorToken = "tok-op", apiKey = "key-ALPHA-0001")
        return BootOrchestrator(config, secrets, WorktreeManager(git, gitRootFor(git), "alpha"), spawner, scope).boot()
    }

    // one gitRoot per RecordingGit instance (so clone/worktree dirs are real + isolated per test).
    private val roots = HashMap<RecordingGit, File>()
    private fun gitRootFor(git: RecordingGit): File = roots.getOrPut(git) { Files.createTempDirectory("cyp247-s2").toFile() }

    @Test
    fun repoChange_reprovisionsOnNextStart_freshCloneFromNewRepo() = runBlocking<Unit> {
        val git = RecordingGit()
        val booted = boot(git, CapturingSpawner())
        assertTrue(git.clonesFrom(repoA, "clones/alpha") >= 1, "boot cloned alpha from repoA")

        booted.projectConfig.setRepo("alpha", repoB, "main")
        booted.repoReprovision.markStale("alpha", discardUnpushed = false)
        booted.runtimeRegistry.active().lifecycle.restart("dev") // consumes the pending re-provision

        assertTrue(git.clonesFrom(repoB, "clones/alpha") >= 1, "next start re-cloned alpha from the NEW repoB")
        assertEquals(null, booted.repoReprovision.pending("alpha"), "the pending mark was cleared after re-provision")
        roots.remove(git)?.deleteRecursively()
    }

    @Test
    fun unpushedWork_blocksReprovision_noSilentLoss() = runBlocking<Unit> {
        val git = RecordingGit(unpushed = true) // dev's agent branch has local commits on no remote → §2d blocks
        val booted = boot(git, CapturingSpawner())

        booted.projectConfig.setRepo("alpha", repoB, "main")
        booted.repoReprovision.markStale("alpha", discardUnpushed = false)
        booted.runtimeRegistry.active().lifecycle.restart("dev")

        assertEquals(0, git.clonesFrom(repoB, "clones/alpha"), "BLOCKED: no re-clone from repoB — unpushed work preserved")
        assertNotNull(booted.repoReprovision.pending("alpha"), "still pending: the operator can push + retry (no silent loss)")
        roots.remove(git)?.deleteRecursively()
    }

    @Test
    fun discardUnpushedOptIn_reprovisionsDespiteUnpushedWork() = runBlocking<Unit> {
        val git = RecordingGit(unpushed = true)
        val booted = boot(git, CapturingSpawner())

        booted.projectConfig.setRepo("alpha", repoB, "main")
        booted.repoReprovision.markStale("alpha", discardUnpushed = true) // D5: operator accepts the loss
        booted.runtimeRegistry.active().lifecycle.restart("dev")

        assertTrue(git.clonesFrom(repoB, "clones/alpha") >= 1, "the opt-in re-provisions to repoB despite unpushed work")
        assertEquals(null, booted.repoReprovision.pending("alpha"), "the pending mark was cleared")
        roots.remove(git)?.deleteRecursively()
    }
}
