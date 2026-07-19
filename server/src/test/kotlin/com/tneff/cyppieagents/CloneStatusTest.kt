package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.CloneState
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.InMemoryCloneStatusStore
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.boot.classifyCloneFailure
import com.tneff.cyppieagents.boot.cloneStatusForWire
import com.tneff.cyppieagents.model.CloneFailReason
import com.tneff.cyppieagents.model.CloneStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * CYP-736 — the clone-status seam teeth (each test's `Mutation:` note names what reddens it):
 *  - WorktreeManager drives the lifecycle it ACTUALLY runs (CLONING before the clone; CLONED_OK / CLONE_FAILED+reason).
 *  - ② invariant: a reason clings ONLY to CLONE_FAILED.
 *  - ③ invariant: a non-null cloneStatus reaches the wire ONLY when configured.
 *  - classifyCloneFailure: single-source, AUTH-first, honest UNKNOWN fallback (the ambiguous line is never guessed).
 */
class CloneStatusTest {

    private val pid = "alpha"
    private val repo = RepoConfig("git@github.com:org/repo.git", "main")

    /** A fake git whose clone exit code + merged output are scripted; [onClone] observes store state mid-clone. */
    private class FakeGit(
        private val cloneExit: Int = 0,
        private val cloneOutput: String = "",
        private val onClone: (() -> Unit)? = null,
    ) : CommandRunner {
        override fun run(command: List<String>, cwd: File): CommandResult =
            if (command.getOrNull(1) == "clone") {
                onClone?.invoke()
                if (cloneExit == 0) File(command.last(), ".git").mkdirs()
                CommandResult(cloneExit, cloneOutput)
            } else {
                CommandResult(0, "")
            }
    }

    private fun manager(git: CommandRunner, gitRoot: File, store: InMemoryCloneStatusStore) =
        WorktreeManager(git, gitRoot, pid, store)

    private fun tempRoot(): File = Files.createTempDirectory("cyp736").toFile()

    // ---- WorktreeManager lifecycle ----

    @Test fun freshCloneSuccessReportsClonedOk() {
        val store = InMemoryCloneStatusStore()
        manager(FakeGit(cloneExit = 0), tempRoot(), store).ensureClone(repo)
        assertEquals(CloneStatus.CLONED_OK, store.get(pid)?.status)
        assertNull(store.get(pid)?.reason) // OK never carries a reason
        // Mutation: drop the final CLONED_OK report → status stays CLONING → reddens.
    }

    @Test fun cloningIsReportedBeforeTheCloneRuns() {
        val store = InMemoryCloneStatusStore()
        var midClone: CloneStatus? = null
        val git = FakeGit(cloneExit = 0, onClone = { midClone = store.get(pid)?.status })
        manager(git, tempRoot(), store).ensureClone(repo)
        assertEquals(CloneStatus.CLONING, midClone) // observed WHILE the clone ran — the spinner-honesty
        // Mutation: move the CLONING report AFTER runner.run → midClone is null → reddens.
    }

    @Test fun authFailureReportsCloneFailedAuth() {
        val store = InMemoryCloneStatusStore()
        val git = FakeGit(
            cloneExit = 128,
            cloneOutput = "remote: Support for password authentication was removed.\nfatal: Authentication failed",
        )
        assertFailsWith<IllegalStateException> { manager(git, tempRoot(), store).ensureClone(repo) }
        assertEquals(CloneStatus.CLONE_FAILED, store.get(pid)?.status)
        assertEquals(CloneFailReason.AUTH, store.get(pid)?.reason)
        // Mutation: swallow the failure (record nothing before throw) → status stays CLONING → reddens.
    }

    @Test fun urlFailureReportsCloneFailedUrlUnreachable() {
        val store = InMemoryCloneStatusStore()
        val git = FakeGit(cloneExit = 128, cloneOutput = "fatal: unable to access '...': Could not resolve host: github.com")
        assertFailsWith<IllegalStateException> { manager(git, tempRoot(), store).ensureClone(repo) }
        assertEquals(CloneStatus.CLONE_FAILED, store.get(pid)?.status)
        assertEquals(CloneFailReason.URL_UNREACHABLE, store.get(pid)?.reason)
    }

    @Test fun alreadyClonedReportsClonedOkWithoutCloning() {
        val root = tempRoot()
        File(root, "clones/$pid/.git").mkdirs() // an existing clone
        val store = InMemoryCloneStatusStore()
        var cloned = false
        val git = object : CommandRunner {
            override fun run(command: List<String>, cwd: File): CommandResult {
                if (command.getOrNull(1) == "clone") cloned = true
                return CommandResult(0, "")
            }
        }
        manager(git, root, store).ensureClone(repo)
        assertEquals(CloneStatus.CLONED_OK, store.get(pid)?.status)
        assertFalse(cloned) // idempotent: an existing clone is CLONED_OK, no re-clone
    }

    @Test fun unconfiguredRepoReportsNothing() {
        val store = InMemoryCloneStatusStore()
        manager(FakeGit(), tempRoot(), store).ensureClone(RepoConfig("", "main")) // blank url → unconfigured
        assertNull(store.get(pid)) // ③: no status recorded → GET null → client decodes NOT_CONFIGURED
        // Mutation: report a status on the unconfigured path → a non-null status leaks for an unset repo → reddens.
    }

    // ---- ② invariant: a reason clings ONLY to CLONE_FAILED ----

    @Test fun reasonRetainedOnlyOnCloneFailed() {
        val store = InMemoryCloneStatusStore()
        store.report(pid, CloneStatus.CLONE_FAILED, CloneFailReason.AUTH)
        assertEquals(CloneFailReason.AUTH, store.get(pid)?.reason)
        store.report(pid, CloneStatus.CLONED_OK, CloneFailReason.AUTH) // a reason passed with a non-FAILED status
        assertNull(store.get(pid)?.reason) // dropped — a reason never clings to OK
        // Mutation: store the reason unconditionally → the OK case keeps AUTH → reddens.
    }

    // ---- ③ invariant: a non-null cloneStatus reaches the wire ONLY when configured ----

    @Test fun cloneStatusForWireNullsWhenNotConfigured() {
        val ok = CloneState(CloneStatus.CLONED_OK)
        assertEquals(ok, cloneStatusForWire(configured = true, state = ok))
        assertNull(cloneStatusForWire(configured = false, state = ok)) // configured=false ⟹ null (never CLONED_OK)
        // Mutation: return `state` regardless of `configured` → the false case leaks CLONED_OK → reddens.
    }

    // ---- classifyCloneFailure fixtures (single-source, AUTH-first, honest UNKNOWN fallback) ----

    @Test fun classifyAuthSignatures() {
        listOf(
            "fatal: Authentication failed for 'https://github.com/org/repo.git/'",
            "fatal: could not read Username for 'https://github.com': terminal prompts disabled",
            "git@github.com: Permission denied (publickey).",
            "remote: Support for password authentication was removed.",
            "The requested URL returned error: 403",
        ).forEach { assertEquals(CloneFailReason.AUTH, classifyCloneFailure(it), it) }
    }

    @Test fun classifyUrlSignatures() {
        listOf(
            "fatal: unable to access '...': Could not resolve host: github.com",
            "remote: Repository not found.",
            "fatal: '/nope' does not appear to be a git repository",
            "ssh: connect to host github.com port 22: Connection refused",
        ).forEach { assertEquals(CloneFailReason.URL_UNREACHABLE, classifyCloneFailure(it), it) }
    }

    @Test fun classifyAmbiguousFallsToUnknown() {
        // The bare ambiguous line prints in BOTH auth AND url failures → it must NOT be guessed as either.
        assertEquals(CloneFailReason.UNKNOWN, classifyCloneFailure("fatal: Could not read from remote repository."))
        assertEquals(CloneFailReason.UNKNOWN, classifyCloneFailure("fatal: something totally unexpected happened"))
        // Mutation: add "could not read from remote" to either signal list → this reddens (a guessed reason).
    }

    @Test fun authWinsWhenBothSignaturesPresent() {
        // Output carrying BOTH an auth line AND a url-ish line resolves to AUTH (most specific, checked first).
        val both = "remote: Repository not found.\nfatal: Authentication failed for 'https://github.com/x'"
        assertEquals(CloneFailReason.AUTH, classifyCloneFailure(both))
        // Mutation: check url before auth → this reddens (URL_UNREACHABLE).
    }
}
