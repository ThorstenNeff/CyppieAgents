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
 * Idempotent git-worktree orchestration (Spec 02 §11): clone **each project's** repo into its own
 * [gitRoot]/clones/<projectId> (CYP-247 S1), then add one worktree per agent under the project-scoped root
 * [gitRoot]/projects/<projectId>/<name> (S12 / CYP-82 — Doc 08 §3). Re-running is a no-op when the clone /
 * worktree already exist. Two projects can therefore target DIFFERENT repos/branches at once, each with its
 * own clone + isolated agent worktrees (Reviewer #3); MVP=1 → clones/default + projects/default/<name>.
 */
class WorktreeManager(
    private val runner: CommandRunner,
    private val gitRoot: File,
    /**
     * The project this manager is scoped to (S12 / CYP-82, CYP-247 S1). Both the clone
     * ([gitRoot]/clones/<projectId>) AND the worktrees ([gitRoot]/projects/<projectId>/) nest under it, so
     * [forProject] yields a fully-isolated per-project manager. Single-sourced from `platform.config.json`
     * (`projectId`) at boot; defaulted so existing constructions resolve to the one MVP project.
     */
    private val activeProjectId: String = DEFAULT_PROJECT_ID,
) {
    private val log = LoggerFactory.getLogger("boot.worktree")
    // CYP-247 S1: per-project clone (was the shared gitRoot/repo). forProject(pid) → clones/<pid>.
    private val repoDir = File(gitRoot, "clones/$activeProjectId")
    private val worktreesDir = File(gitRoot, "projects/$activeProjectId")

    /** Parent dir of all agent worktrees (the connector's worktreesRoot). */
    val worktreesRoot: File get() = worktreesDir

    /**
     * CYP-255 (.4a) / CYP-247 S1 — a sibling manager fully scoped to [projectId] (its OWN clone
     * `clones/<projectId>` + worktrees `projects/<projectId>/`), sharing only [runner] + [gitRoot]. The
     * [ProjectRuntimeFactory] mints one per project runtime so a spawn lands in that project's clone+root;
     * [ensureClone] is LAZY (D6) — called on first worktree need for a non-boot project.
     */
    fun forProject(projectId: String): WorktreeManager = WorktreeManager(runner, gitRoot, projectId)

    /** Clone [repo] into this project's [repoDir] (`clones/<projectId>`) if not already a git repo. Idempotent. */
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
     * CYP-247 S4 — **ADOPT** a legacy shared clone into this project's per-project clone dir WITHOUT
     * re-cloning (§6.1 refinement — Rule ①: NEVER wipe a project that has local worktrees; the
     * Auftraggeber's `CLAUDE.md` lives in `projects/<pid>/<agent>`). If the legacy `gitRoot/repo` clone
     * exists AND this project's `clones/<pid>` is still absent: **move** (not copy) `repo` → `clones/<pid>`
     * (byte-preserved, atomic on the same filesystem), then `git worktree repair` the project's worktrees so
     * their `.git` gitlinks re-point at the moved clone. Idempotent: no legacy clone, or the per-project
     * clone already present → **no-op returns false**. Returns true iff an adoption happened.
     */
    fun adoptLegacyClone(): Boolean {
        val legacy = File(gitRoot, "repo")
        if (!File(legacy, ".git").exists()) return false // no legacy shared clone → nothing to adopt
        if (File(repoDir, ".git").exists()) return false // this project already owns its clone → no-op
        repoDir.parentFile?.mkdirs()
        check(legacy.renameTo(repoDir)) { "failed to adopt legacy clone $legacy → $repoDir" }
        // Repair the worktree admin links (the .git gitlinks under projects/<pid>/* now point at the moved clone).
        val worktreePaths = worktreesDir.listFiles()?.filter { it.isDirectory }?.map { it.absolutePath } ?: emptyList()
        if (worktreePaths.isNotEmpty()) {
            val res = runner.run(listOf("git", "worktree", "repair") + worktreePaths, repoDir)
            check(res.exitCode == 0) { "git worktree repair failed after adopt (exit ${res.exitCode})" }
        }
        log.info("adopted legacy clone into {} + repaired {} worktree(s) — worktrees/CLAUDE.md byte-preserved", repoDir, worktreePaths.size)
        return true
    }

    /** CYP-247 S4 — the origin remote URL of this project's clone (for the boot reconciler's mismatch check),
     *  or null if the clone is absent/unreadable. */
    fun cloneRemoteUrl(): String? {
        if (!File(repoDir, ".git").exists()) return null
        val res = runner.run(listOf("git", "remote", "get-url", "origin"), repoDir)
        return if (res.exitCode == 0) res.output.trim().ifBlank { null } else null
    }

    /** CYP-247 S4 — project dirs under `projects/` whose id is NOT in [knownProjectIds] (residual/orphaned,
     *  e.g. a deleted project or a stale legacy layout). Fail-closed: empty if `projects/` is absent. */
    fun residualProjectDirs(knownProjectIds: Set<String>): List<String> =
        File(gitRoot, "projects").listFiles()?.filter { it.isDirectory && it.name !in knownProjectIds }?.map { it.name } ?: emptyList()

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
     * CYP-247 S2 (§2d) — the **safe-teardown work-guard**: the list of this project's agent worktrees that
     * hold work a destructive teardown (S2 re-provision, later S4 migration) would **silently destroy**.
     * Checks BOTH, per worktree:
     *  - **(i) dirty tree** — `git status --porcelain` in the worktree is non-empty (uncommitted changes).
     *  - **(ii) unpushed commits** — the `agent/<name>` branch has commits present on NO remote:
     *    `git log --oneline agent/<name> --not --remotes` is non-empty. This is upstream-agnostic, so a
     *    fresh branch sitting at the (already-pushed) base branch is NOT falsely flagged, while any local
     *    commit that was never pushed to any remote IS.
     * Returns a human list (`"<name> (uncommitted changes + unpushed commits)"`); **empty = safe to tear
     * down**. Single-sourced so S2 and S4 use the identical definition of "unsafe".
     */
    fun unpushedWork(): List<String> {
        val worktrees = worktreesDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val atRisk = mutableListOf<String>()
        for (wt in worktrees) {
            val dirty = runner.run(listOf("git", "status", "--porcelain"), wt).output.isNotBlank()
            val unpushed = runner.run(
                listOf("git", "log", "--oneline", "agent/${wt.name}", "--not", "--remotes"),
                repoDir,
            ).output.isNotBlank()
            if (dirty || unpushed) {
                val reasons = buildList {
                    if (dirty) add("uncommitted changes")
                    if (unpushed) add("unpushed commits")
                }
                atRisk += "${wt.name} (${reasons.joinToString(" + ")})"
            }
        }
        return atRisk
    }

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
     * Remove an ENTIRE project's worktree root `projects/<projectId>/` **and its own clone
     * `clones/<projectId>/`** — the worktree partition of the project cascade-delete (S13 / CYP-91,
     * extended by CYP-247 S1). Unlike [deleteWorktree] this targets [projectId] explicitly (NOT the
     * instance's [activeProjectId]), because delete always operates on a NON-active project. Each agent
     * worktree is `git worktree remove --force`d **in that project's OWN clone** (where it is registered —
     * CYP-247 S1: worktrees are no longer registered in a shared clone), then `git worktree prune` clears
     * dangling registrations, and finally BOTH the worktree dir and the per-project clone are removed (no
     * dangling clone leak). Agent branches `agent/<…>` live in the clone that is now deleted, so their
     * commits are gone with it (the clone WAS the project's checkout; a project delete is terminal — the
     * §9.3 "keep the branch" applies to a single-agent remove, not a whole-project cascade). Returns the
     * number of agent worktrees removed (for honest no-orphan reporting).
     *
     * **Fail-closed scoping (the no-cross-project guard):** a blank [projectId], or any value whose
     * resolved dir is not STRICTLY below `projects/` (resp. `clones/`), removes NOTHING — so the teardown
     * can never walk up to a root and take out siblings. Idempotent: absent dirs are a no-op.
     */
    fun deleteProject(projectId: String): Int {
        if (projectId.isBlank()) return 0 // fail-closed: never resolve to a root
        val projectsRoot = File(gitRoot, "projects").canonicalFile
        val projectDir = File(gitRoot, "projects/$projectId").canonicalFile
        // Defense-in-depth: the target must be a strict child of projects/ (guards against `..`/escape
        // even though SAFE_ID already rejected such ids at the registry boundary).
        if (projectDir == projectsRoot || projectDir.parentFile != projectsRoot) return 0

        // CYP-247 S1: operate on the DELETED project's OWN clone (its worktrees register there), strict-child guarded.
        val clonesRoot = File(gitRoot, "clones").canonicalFile
        val projectClone = File(gitRoot, "clones/$projectId").canonicalFile
        val cloneUsable = projectClone != clonesRoot && projectClone.parentFile == clonesRoot &&
            File(projectClone, ".git").exists()

        var removed = 0
        if (projectDir.exists()) {
            projectDir.listFiles()?.filter { it.isDirectory }?.forEach { worktree ->
                if (cloneUsable) {
                    val res = runner.run(listOf("git", "worktree", "remove", "--force", worktree.absolutePath), projectClone)
                    check(res.exitCode == 0) { "git worktree remove failed for '${worktree.name}' (exit ${res.exitCode})" }
                }
                removed++
            }
            projectDir.deleteRecursively()
        }
        if (cloneUsable) {
            runner.run(listOf("git", "worktree", "prune"), projectClone) // clear any dangling registration
            projectClone.deleteRecursively()                             // drop the per-project clone (no leak)
        }
        return removed
    }
}
