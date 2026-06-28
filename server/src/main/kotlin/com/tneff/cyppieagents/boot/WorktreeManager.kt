package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
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
 * then add one worktree per agent under the **project-scoped** root [gitRoot]/projects/<projectId>/<name>
 * (S12 / CYP-82 — Doc 08 §3). Re-running is a no-op when the clone / worktree already exist. Each
 * agent gets an ISOLATED working folder (Reviewer #3); MVP=1 → projects/default/<name>.
 */
class WorktreeManager(
    private val runner: CommandRunner,
    private val gitRoot: File,
    /**
     * Active project (S12 / CYP-82). Worktrees nest under projects/<projectId>/, single-sourced from
     * `platform.config.json` (`projectId`). Defaulted so existing constructions resolve to the one MVP
     * project. The repo clone itself stays project-agnostic at [gitRoot]/repo (one remote, many
     * project-scoped worktrees).
     */
    private val activeProjectId: String = DEFAULT_PROJECT_ID,
) {
    private val log = LoggerFactory.getLogger("boot.worktree")
    private val repoDir = File(gitRoot, "repo")
    private val worktreesDir = File(gitRoot, "projects/$activeProjectId")

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
     * worktree dir is reused. Each agent gets its OWN branch `agent/<name>` created off [baseBranch]
     * — you cannot `git worktree add` the base branch itself, since it is already checked out in the
     * main clone. The per-agent branch also matches the project's branch-per-agent strategy.
     * If the branch already exists (e.g. a re-add after the dir was removed), reuse it without `-b`.
     */
    fun ensureWorktree(worktreeName: String, baseBranch: String): File {
        val target = File(worktreesDir, worktreeName)
        if (target.exists()) {
            log.info("worktree already present at {}", target)
            return target
        }
        worktreesDir.mkdirs()
        val agentBranch = "agent/$worktreeName"
        val branchExists = runner.run(
            listOf("git", "rev-parse", "--verify", "--quiet", agentBranch),
            repoDir,
        ).exitCode == 0
        val command = if (branchExists) {
            listOf("git", "worktree", "add", target.absolutePath, agentBranch)
        } else {
            listOf("git", "worktree", "add", "-b", agentBranch, target.absolutePath, baseBranch)
        }
        val res = runner.run(command, repoDir)
        check(res.exitCode == 0) { "git worktree add failed for '$worktreeName' (exit ${res.exitCode})" }
        return target
    }

    fun worktreeDir(worktreeName: String): File = File(worktreesDir, worktreeName)

    /**
     * Remove an agent's worktree (S14 / CYP-97 — the destructive `?worktree=delete` path). Uses
     * `git worktree remove --force` so an unclean worktree is removed too (the UI warns about lost
     * uncommitted/unpushed work, AGENT-MANAGEMENT §5). The agent branch `agent/<name>` is **NOT**
     * deleted (PO decision §9.3) — its commits survive. No-op if the worktree is already gone.
     */
    fun deleteWorktree(worktreeName: String) {
        val target = File(worktreesDir, worktreeName)
        if (!target.exists()) {
            log.info("worktree already absent at {}", target)
            return
        }
        val res = runner.run(listOf("git", "worktree", "remove", "--force", target.absolutePath), repoDir)
        check(res.exitCode == 0) { "git worktree remove failed for '$worktreeName' (exit ${res.exitCode})" }
    }
}
