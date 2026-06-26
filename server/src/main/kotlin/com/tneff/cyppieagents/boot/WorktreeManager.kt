package com.tneff.cyppieagents.boot

import org.slf4j.LoggerFactory
import java.io.File

/** Result of running an external command. */
data class CommandResult(val exitCode: Int, val output: String)

/** Injectable command runner so the boot's git calls are testable without a real repo. */
interface CommandRunner {
    fun run(command: List<String>, cwd: File): CommandResult
}

/** Real runner over [ProcessBuilder]; stdout+stderr merged into [CommandResult.output]. */
class ProcessCommandRunner : CommandRunner {
    override fun run(command: List<String>, cwd: File): CommandResult {
        cwd.mkdirs()
        val process = ProcessBuilder(command).directory(cwd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return CommandResult(exit, output)
    }
}

/**
 * Idempotent git-worktree orchestration (Spec 02 §11): clone the repo once into [gitRoot]/repo,
 * then add one worktree per agent under [gitRoot]/worktrees/<name>. Re-running is a no-op when the
 * clone / worktree already exist. Each agent gets an ISOLATED working folder (Reviewer #3).
 */
class WorktreeManager(
    private val runner: CommandRunner,
    private val gitRoot: File,
) {
    private val log = LoggerFactory.getLogger("boot.worktree")
    private val repoDir = File(gitRoot, "repo")
    private val worktreesDir = File(gitRoot, "worktrees")

    /** Parent dir of all agent worktrees (the connector's worktreesRoot). */
    val worktreesRoot: File get() = worktreesDir

    /** Clone [repo] into [repoDir] if not already a git repo. */
    fun ensureClone(repo: RepoConfig) {
        if (File(repoDir, ".git").exists()) {
            log.info("repo clone already present at {}", repoDir)
            return
        }
        gitRoot.mkdirs()
        val res = runner.run(listOf("git", "clone", "--branch", repo.branch, repo.url, repoDir.absolutePath), gitRoot)
        check(res.exitCode == 0) { "git clone failed (exit ${res.exitCode})" }
    }

    /**
     * Ensure a worktree exists for the agent and return its directory. Idempotent: an existing
     * worktree dir is reused. The branch is per worktree (created off the repo branch on first add).
     */
    fun ensureWorktree(worktreeName: String, branch: String): File {
        val target = File(worktreesDir, worktreeName)
        if (target.exists()) {
            log.info("worktree already present at {}", target)
            return target
        }
        worktreesDir.mkdirs()
        val res = runner.run(
            listOf("git", "worktree", "add", target.absolutePath, branch),
            repoDir,
        )
        check(res.exitCode == 0) { "git worktree add failed for '$worktreeName' (exit ${res.exitCode})" }
        return target
    }

    fun worktreeDir(worktreeName: String): File = File(worktreesDir, worktreeName)
}
