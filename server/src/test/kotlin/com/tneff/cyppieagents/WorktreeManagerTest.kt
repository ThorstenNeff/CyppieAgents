package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.WorktreeManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression guard for CYP-29 against the realistic [FakeGit] (models checked-out branches).
 * With this fake, the OLD `git worktree add <target> main` (base already checked out) goes red,
 * and the NEW per-agent `-b agent/<name>` goes green.
 */
class WorktreeManagerTest {

    private fun gitRoot() = Files.createTempDirectory("wt-mgr").toFile()

    @Test
    fun createsPerAgentBranchOffBase() {
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        wm.ensureClone(RepoConfig("u", "main"))
        wm.ensureWorktree("backend", "main")

        // New code: a per-agent branch created with -b off the base.
        assertTrue(git.issued(listOf("git", "worktree", "add", "-b", "agent/backend")), "expected -b agent/backend")
        // It must NOT add the checked-out base branch directly (the old bug).
        assertFalse(
            git.commands.any {
                it.take(3) == listOf("git", "worktree", "add") && it.getOrNull(3) != "-b" && it.lastOrNull() == "main"
            },
            "must not add the checked-out base 'main' directly",
        )
    }

    @Test
    fun addingTheCheckedOutBaseDirectlyFails_regressionGuard() {
        // Documents the modelled bug: the fake rejects adding the checked-out base, so a revert to
        // the old `worktree add <target> main` would turn this (and the boot tests) red in CI.
        val git = FakeGit()
        val root = gitRoot()
        WorktreeManager(git, root).ensureClone(RepoConfig("u", "main"))
        val result = git.run(
            listOf("git", "worktree", "add", File(root, "worktrees/x").absolutePath, "main"),
            File(root, "repo"),
        )
        assertNotEquals(0, result.exitCode, "adding the already-checked-out base must fail")
    }

    @Test
    fun reusesExistingAgentBranchWithoutDashB() {
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        wm.ensureClone(RepoConfig("u", "main"))
        // The agent branch already exists (e.g. its worktree was removed but the branch remains).
        git.seedBranch("agent/backend")

        wm.ensureWorktree("backend", "main")

        val target = File(root, "worktrees/backend").absolutePath
        assertTrue(git.issued(listOf("git", "worktree", "add", target, "agent/backend")), "reuse path adds existing branch")
        assertFalse(git.commands.any { it.contains("-b") }, "reuse path must not pass -b")
    }
}
