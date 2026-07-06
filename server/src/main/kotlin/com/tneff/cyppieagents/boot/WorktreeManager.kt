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

    /**
     * CYP-255 (.4a) — a sibling manager scoped to [projectId], reusing this one's shared clone
     * ([runner] + [gitRoot], so `projects/<projectId>/`). The [ProjectRuntimeFactory] mints one per
     * project runtime so a spawn lands in that project's worktree root; the shared `repo` clone
     * ([ensureClone]) stays project-agnostic and is done once at boot on the base manager.
     */
    fun forProject(projectId: String): WorktreeManager = WorktreeManager(runner, gitRoot, projectId)

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

    /**
     * Remove an ENTIRE project's worktree root `projects/<projectId>/` — the worktree partition of the
     * project cascade-delete (S13 / CYP-91). Unlike [deleteWorktree] this targets [projectId]
     * explicitly (NOT the instance's [activeProjectId]), because delete always operates on a NON-active
     * project. Each agent worktree under the project is `git worktree remove --force`d (so the shared
     * clone's metadata is cleaned), then the dir is removed and `git worktree prune` clears any
     * dangling registration. Agent branches `agent/<…>` are **kept** (PO decision §9.3) — commits
     * survive. Returns the number of agent worktrees removed (for honest no-orphan reporting).
     *
     * **Fail-closed scoping (the no-cross-project guard):** a blank [projectId], or any value whose
     * resolved dir is not STRICTLY below `projects/`, removes NOTHING — so the teardown can never walk
     * up to the projects root and take out sibling projects. Idempotent: an absent project dir is a no-op.
     */
    fun deleteProject(projectId: String): Int {
        if (projectId.isBlank()) return 0 // fail-closed: never resolve to the projects root
        val projectsRoot = File(gitRoot, "projects").canonicalFile
        val projectDir = File(gitRoot, "projects/$projectId").canonicalFile
        // Defense-in-depth: the target must be a strict child of projects/ (guards against `..`/escape
        // even though SAFE_ID already rejected such ids at the registry boundary).
        if (projectDir == projectsRoot || projectDir.parentFile != projectsRoot) return 0
        if (!projectDir.exists()) return 0

        var removed = 0
        projectDir.listFiles()?.filter { it.isDirectory }?.forEach { worktree ->
            val res = runner.run(listOf("git", "worktree", "remove", "--force", worktree.absolutePath), repoDir)
            check(res.exitCode == 0) { "git worktree remove failed for '${worktree.name}' (exit ${res.exitCode})" }
            removed++
        }
        projectDir.deleteRecursively()
        // Clean any dangling worktree registration the remove didn't (e.g. a manually-deleted dir).
        runner.run(listOf("git", "worktree", "prune"), repoDir)
        return removed
    }
}
