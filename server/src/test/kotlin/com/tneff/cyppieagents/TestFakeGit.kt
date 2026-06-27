package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import java.io.File

/**
 * Realistic fake git that models **checked-out-branch** semantics, so the unit tests actually guard
 * the worktree regression (CYP-29): `git worktree add <target> <branch>` WITHOUT `-b` fails when
 * `<branch>` is already checked out — which is exactly what breaks the real boot when the base
 * branch (`main`, checked out in the clone) is added directly. A fake that returned 0 for everything
 * would let a revert to the buggy code pass CI; this one turns it red.
 */
internal class FakeGit : CommandRunner {
    val commands = mutableListOf<List<String>>()
    private val existingBranches = mutableSetOf<String>()
    private val checkedOut = mutableSetOf<String>()

    /** Pre-seed an existing-but-not-checked-out branch (covers the reuse path). */
    fun seedBranch(name: String) { existingBranches += name }

    override fun run(command: List<String>, cwd: File): CommandResult {
        commands += command
        return when {
            command.getOrNull(1) == "clone" -> {
                File(command.last(), ".git").mkdirs()
                existingBranches += "main"; checkedOut += "main" // base is checked out in the clone
                CommandResult(0, "")
            }
            command.getOrNull(1) == "rev-parse" -> {
                val branch = command.last()
                CommandResult(if (branch in existingBranches) 0 else 1, "")
            }
            command.getOrNull(1) == "worktree" && command.getOrNull(2) == "add" -> {
                if (command.getOrNull(3) == "-b") {
                    val branch = command[4]; val target = command[5]
                    if (branch in checkedOut) return CommandResult(128, "fatal: '$branch' is already used by a worktree")
                    existingBranches += branch; checkedOut += branch; File(target).mkdirs()
                    CommandResult(0, "")
                } else {
                    val target = command[3]; val branch = command[4]
                    // THE BUG the fix addresses: adding an already-checked-out branch fails.
                    if (branch in checkedOut) return CommandResult(128, "fatal: '$branch' is already checked out")
                    checkedOut += branch; File(target).mkdirs()
                    CommandResult(0, "")
                }
            }
            else -> CommandResult(0, "")
        }
    }

    fun count(vararg prefix: String) = commands.count { it.take(prefix.size) == prefix.toList() }
    fun issued(prefix: List<String>) = commands.any { it.take(prefix.size) == prefix }
}
