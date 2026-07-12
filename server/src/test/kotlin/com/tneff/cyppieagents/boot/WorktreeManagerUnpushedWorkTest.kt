package com.tneff.cyppieagents.boot

import com.tneff.cyppieagents.model.AtRiskAgent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-466 — [WorktreeManager.unpushedWork] returns the TYPED per-agent at-risk list (the block guard + the
 * discard-confirm preview single-source it). Proves: a dirty tree → `uncommitted`, an unpushed `agent/<name>`
 * branch → `unpushed`, both independently, and a CLEAN worktree is excluded. The fake runner keys off the git
 * subcommand + cwd, so dropping either check (mutation) drops that worktree/flag and reds.
 */
class WorktreeManagerUnpushedWorkTest {

    /** A fake git: `status --porcelain` is non-blank for [dirty] worktrees; `log agent/<name> …` is non-blank for
     *  [unpushed] branches. Everything else blank (clean). */
    private fun fakeGit(dirty: Set<String>, unpushed: Set<String>) = object : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult = when {
            command.contains("status") -> CommandResult(0, if (cwd.name in dirty) " M file.kt" else "")
            command.contains("log") -> {
                val name = command.first { it.startsWith("agent/") }.removePrefix("agent/")
                CommandResult(0, if (name in unpushed) "abc123 local commit" else "")
            }
            else -> CommandResult(0, "")
        }
    }

    private fun worktreeRootWith(vararg names: String): File {
        val root = Files.createTempDirectory("wtm-unpushed").toFile()
        val projects = File(root, "projects/default")
        names.forEach { File(projects, it).mkdirs() }
        return root
    }

    @Test
    fun typedPerAgent_dirtyAndUnpushedDetectedIndependently_cleanExcluded() {
        val root = worktreeRootWith("alice", "bob", "clean")
        val wtm = WorktreeManager(fakeGit(dirty = setOf("alice"), unpushed = setOf("bob")), root)

        val byAgent = wtm.unpushedWork().associateBy { it.worktree }

        assertEquals(setOf("alice", "bob"), byAgent.keys, "the CLEAN worktree is excluded; only alice(dirty) + bob(unpushed) are at risk")
        assertEquals(AtRiskAgent("alice", uncommitted = true, unpushed = false), byAgent["alice"], "alice = uncommitted only")
        assertEquals(AtRiskAgent("bob", uncommitted = false, unpushed = true), byAgent["bob"], "bob = unpushed only")
    }

    @Test
    fun bothReasonsOnOneAgent() {
        val root = worktreeRootWith("carol")
        val wtm = WorktreeManager(fakeGit(dirty = setOf("carol"), unpushed = setOf("carol")), root)
        assertEquals(listOf(AtRiskAgent("carol", uncommitted = true, unpushed = true)), wtm.unpushedWork())
    }

    @Test
    fun allClean_isEmpty() {
        val root = worktreeRootWith("alice", "bob")
        val wtm = WorktreeManager(fakeGit(dirty = emptySet(), unpushed = emptySet()), root)
        assertEquals(emptyList(), wtm.unpushedWork(), "no dirty/unpushed work → empty → safe to tear down")
    }
}
