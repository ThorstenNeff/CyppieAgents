package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.WorktreeManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-247 S4 — the legacy-migration / reconciler primitives on [WorktreeManager]. The load-bearing one is
 * [WorktreeManager.adoptLegacyClone]: it must MOVE a legacy `gitRoot/repo` into `clones/<pid>` and repair the
 * worktree links WITHOUT touching the worktree files — **Rule ①: the Auftraggeber's CLAUDE.md is byte-preserved,
 * never wiped by a re-clone-fresh.**
 */
class WorktreeManagerS4Test {

    private fun gitRoot() = Files.createTempDirectory("wt-s4").toFile()

    /** Records commands; a `remote get-url` returns [remoteUrl]; everything else exits 0. No clone/worktree side effects. */
    private class RecordingGit(private val remoteUrl: String = "git@github.com:org/repo.git") : CommandRunner {
        val commands = CopyOnWriteArrayList<List<String>>()
        override fun run(command: List<String>, cwd: File): CommandResult {
            commands.add(command)
            return if (command.getOrNull(1) == "remote" && command.getOrNull(2) == "get-url") CommandResult(0, remoteUrl)
            else CommandResult(0, "")
        }
        fun issued(prefix: List<String>) = commands.any { it.size >= prefix.size && it.subList(0, prefix.size) == prefix }
    }

    @Test
    fun adoptLegacyClone_movesRepoToPerProjectClone_repairsWorktrees_bytePreservesFiles() {
        val git = RecordingGit()
        val root = gitRoot()
        File(root, "repo/.git").mkdirs()                              // the LEGACY shared clone
        File(root, "projects/default/backend").mkdirs()
        val claudeMd = File(root, "projects/default/backend/CLAUDE.md").apply { writeText("# Backend persona (Auftraggeber)") }
        val wm = WorktreeManager(git, root, "default")

        assertTrue(wm.adoptLegacyClone(), "a legacy repo with no per-project clone yet → adopted")
        assertFalse(File(root, "repo").exists(), "the legacy gitRoot/repo was MOVED (not left behind)")
        assertTrue(File(root, "clones/default/.git").isDirectory, "the clone now lives at clones/default")
        // Rule ①: the worktree + its CLAUDE.md are byte-identical (a re-clone-fresh would have wiped them).
        assertTrue(File(root, "projects/default/backend").isDirectory, "the worktree dir is preserved")
        assertEquals("# Backend persona (Auftraggeber)", claudeMd.readText(), "the CLAUDE.md is byte-preserved")
        // the .git gitlinks were repaired against the moved clone.
        assertTrue(git.issued(listOf("git", "worktree", "repair")), "git worktree repair was issued after the move")

        assertFalse(wm.adoptLegacyClone(), "idempotent: a second adopt (clone already per-project) is a no-op")
    }

    @Test
    fun adopt_noLegacy_isNoop() {
        val wm = WorktreeManager(RecordingGit(), gitRoot(), "default")
        assertFalse(wm.adoptLegacyClone(), "no legacy gitRoot/repo → no-op false")
    }

    @Test
    fun cloneRemoteUrl_readsOrigin_orNullWhenAbsent() {
        val root = gitRoot()
        val wm = WorktreeManager(RecordingGit(remoteUrl = "git@github.com:org/flutterdriver.git"), root, "default")
        assertNull(wm.cloneRemoteUrl(), "no clone → null")
        File(root, "clones/default/.git").mkdirs()
        assertEquals("git@github.com:org/flutterdriver.git", wm.cloneRemoteUrl(), "reads the clone's origin remote")
    }

    @Test
    fun residualProjectDirs_excludesKnownProjects() {
        val root = gitRoot()
        listOf("alpha", "beta", "orphan").forEach { File(root, "projects/$it").mkdirs() }
        val wm = WorktreeManager(RecordingGit(), root, "alpha")
        assertEquals(listOf("orphan"), wm.residualProjectDirs(setOf("alpha", "beta")), "only the unregistered dir is residual")
        assertEquals(emptyList(), wm.residualProjectDirs(setOf("alpha", "beta", "orphan")), "all-known → nothing residual")
    }
}
