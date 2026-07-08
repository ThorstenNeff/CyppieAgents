package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.WorktreeManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The worktree partition of the project cascade-delete (S13 / CYP-91). The heaviest proofs for the
 * most destructive op: removing project A's worktree root must take out EXACTLY `projects/A/` and
 * leave `projects/B/` completely intact (**no-cross-project**), remove all of A's worktrees
 * (**no-orphan**), keep agent branches, and — critically — never resolve up to the projects root and
 * wipe every project (**fail-closed** on a blank/escaping key).
 */
class WorktreeManagerDeleteProjectTest {

    private fun gitRoot() = Files.createTempDirectory("wt-del-cyp91").toFile()

    /** Create real worktree dirs `projects/<project>/<name>` so deleteProject's fs teardown has something to remove. */
    private fun seedWorktree(root: File, project: String, name: String): File =
        File(root, "projects/$project/$name").apply { mkdirs() }

    @Test
    fun deleteProject_removesOnlyTargetProject_inclItsOwnClone_keepsSiblings() {
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        // CYP-247 S1: each project has its OWN clone (clones/<pid>) where its worktrees are registered. Seed
        // per-project clones so deleteProject removes each project's worktrees in ITS clone, then the clone.
        wm.forProject("alpha").ensureClone(RepoConfig("u", "main")) // → clones/alpha/.git (FakeGit)
        wm.forProject("beta").ensureClone(RepoConfig("u", "main"))  // → clones/beta/.git
        val alphaPo = seedWorktree(root, "alpha", "po")
        val alphaBe = seedWorktree(root, "alpha", "backend")
        val betaPo = seedWorktree(root, "beta", "po")

        val removed = wm.deleteProject("alpha")

        assertEquals(2, removed, "return count = exactly alpha's worktrees removed")
        // no-orphan: alpha's whole worktree root AND its own clone are gone (CYP-247 S1: no dangling clone).
        assertFalse(File(root, "projects/alpha").exists(), "alpha project dir removed")
        assertFalse(File(root, "clones/alpha").exists(), "alpha's own clone removed (no dangling-clone leak)")
        assertTrue(git.issued(listOf("git", "worktree", "remove", "--force", alphaPo.absolutePath)))
        assertTrue(git.issued(listOf("git", "worktree", "remove", "--force", alphaBe.absolutePath)))
        // no-cross-project: beta's worktrees AND clone are byte-for-byte intact.
        assertTrue(betaPo.exists(), "no-cross-project: beta worktree untouched")
        assertTrue(File(root, "projects/beta").exists(), "no-cross-project: beta project dir untouched")
        assertTrue(File(root, "clones/beta").exists(), "no-cross-project: beta clone untouched")
        assertFalse(git.issued(listOf("git", "worktree", "remove", "--force", betaPo.absolutePath)), "beta not removed")
        // agent branches survive as `git branch -d` is never issued (the clone deletion is a dir op, not a branch op).
        assertFalse(git.commands.any { it.take(2) == listOf("git", "branch") }, "no explicit branch delete")
    }

    @Test
    fun deleteProject_blankProjectId_failsClosed_touchesNothing() {
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        wm.ensureClone(RepoConfig("u", "main"))
        val alphaPo = seedWorktree(root, "alpha", "po")
        val betaPo = seedWorktree(root, "beta", "po")

        val removed = wm.deleteProject("")

        assertEquals(0, removed, "fail-closed: a blank projectId removes nothing")
        // The projects root and ALL projects survive — a blank key must never resolve to projects/.
        assertTrue(alphaPo.exists() && betaPo.exists(), "no project touched on blank key")
        assertTrue(File(root, "projects").exists(), "projects root never removed")
        assertFalse(git.commands.any { it.take(3) == listOf("git", "worktree", "remove") }, "no worktree remove issued")
    }

    @Test
    fun deleteProject_escapingKey_failsClosed() {
        // Defense-in-depth: even a path-escaping id (SAFE_ID already rejects these upstream) must not
        // walk above projects/ — the resolved dir is not a strict child, so nothing is removed.
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        wm.ensureClone(RepoConfig("u", "main"))
        val betaPo = seedWorktree(root, "beta", "po")

        assertEquals(0, wm.deleteProject("../.."), "escaping key removes nothing")
        assertTrue(betaPo.exists() && File(root, "projects").exists(), "no sibling/root touched")
    }

    @Test
    fun deleteProject_absentProject_isNoop() {
        val git = FakeGit()
        val root = gitRoot()
        val wm = WorktreeManager(git, root)
        wm.ensureClone(RepoConfig("u", "main"))

        assertEquals(0, wm.deleteProject("ghost"), "absent project → no-op")
        assertFalse(git.commands.any { it.take(3) == listOf("git", "worktree", "remove") })
    }
}
