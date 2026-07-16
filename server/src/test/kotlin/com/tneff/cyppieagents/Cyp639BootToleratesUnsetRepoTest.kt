package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.CommandResult
import com.tneff.cyppieagents.boot.CommandRunner
import com.tneff.cyppieagents.boot.HubConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-639 (Bug, High) — **boot must tolerate an unset / placeholder / uncloneable `repo.url`**, or the hub never comes
 * up and the operator can never reach the GUI that would let them set the repo (chicken-and-egg with CYP-629 first-run).
 *
 * Reproduces the live-box failure the CYP-637 harness surfaced (`git clone failed (exit 128)` at
 * `WorktreeManager.ensureClone` → boot aborts): a fresh `.deb`/`.msi` provision defaults `repo.url` to the
 * `REPLACE_ME_*` placeholder ([RepoConfig.REPO_URL_PLACEHOLDER]) the operator replaces via the GUI.
 *
 * Two guarantees, both mutation-proven:
 *  - an UNCONFIGURED repo (placeholder/blank) → [WorktreeManager.ensureClone] is a NO-OP (skip, no clone attempt);
 *  - a CONFIGURED-but-uncloneable repo → boot at the eager site tolerates the clone failure (logs, comes up DEGRADED).
 */
class Cyp639BootToleratesUnsetRepoTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    /** A runner that FAILS `git clone` (exit 128 — the real "uncloneable" failure the placeholder produces) and
     *  succeeds every other command. Records commands so we can assert whether a clone was even ATTEMPTED. */
    private class CloneFailsGit : CommandRunner {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, cwd: File): CommandResult {
            commands += command
            return if (command.getOrNull(1) == "clone") CommandResult(128, "fatal: repository not found")
            else CommandResult(0, "")
        }
        fun cloneAttempts() = commands.count { it.getOrNull(1) == "clone" }
    }

    private class FakeProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private class FakeSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>): AgentProcess = FakeProcess()
    }

    private fun config(repo: RepoConfig) = PlatformConfig(
        repo = repo,
        hub = HubConfig(),
        agents = listOf(
            AgentConfig("po", "PO", Role.PO),
            AgentConfig("backend", "BE", Role.WORKER),
        ),
    )

    private fun secrets() = Secrets(
        agentTokens = mapOf("tok-po" to "po", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        apiKey = null,
    )

    private fun gitRoot() = Files.createTempDirectory("cyp639-boot").toFile()

    @Test
    fun ensureClone_isNoOp_forPlaceholderOrBlankRepo() {
        // The tightest seam — the exact line that threw on the live box. With the fix, an unconfigured repo never
        // reaches `git clone` (so it can't fail). Mutation: drop the isConfigured guard in ensureClone → the clone is
        // attempted → CloneFailsGit returns 128 → check(exitCode==0) throws → this test reds.
        for (repo in listOf(RepoConfig(RepoConfig.REPO_URL_PLACEHOLDER, "main"), RepoConfig("", "main"), RepoConfig("   ", "main"))) {
            val git = CloneFailsGit()
            WorktreeManager(git, gitRoot()).ensureClone(repo) // must NOT throw
            assertEquals(0, git.cloneAttempts(), "unconfigured repo (url='${repo.url}') → NO clone attempt")
        }
        // A configured repo still clones (the fix does not disable normal cloning). Uses the shared FakeGit whose
        // clone SUCCEEDS, so this asserts the clone happens — not the failure path.
        val git = FakeGit()
        WorktreeManager(git, gitRoot()).ensureClone(RepoConfig("git@github.com:org/repo.git", "main"))
        assertEquals(1, git.count("git", "clone"), "a real repo IS cloned (exactly once)")
    }

    @Test
    fun boot_completesWithPlaceholderRepo_noCloneNoAbort() {
        // The PO's stated gate: a fresh provision-config (placeholder repo) boots to a live hub without a repo. The
        // eager boot clone (BootOrchestrator) skips; the hub state is up. Mutation: drop the ensureClone guard →
        // the placeholder clone is attempted → cloneAttempts()>0 → this assertEquals reds.
        val git = CloneFailsGit()
        val booted = BootOrchestrator(
            config(RepoConfig(RepoConfig.REPO_URL_PLACEHOLDER, "main")), secrets(),
            WorktreeManager(git, gitRoot()), FakeSpawner(), scope,
        ).boot()
        assertEquals(0, git.cloneAttempts(), "placeholder repo → boot attempts NO clone (never aborts on exit 128)")
        assertTrue(booted.state.channels.isNotEmpty(), "hub is up (hub-and-spoke channels derived) despite no repo")
    }

    @Test
    fun boot_toleratesConfiguredButUncloneableRepo_comesUpDegraded() {
        // "unklonbaren repo tolerieren": a CONFIGURED but uncloneable repo (bad URL / no network / auth) must not
        // abort boot at the eager clone site. Mutation: revert the runCatching wrap at BootOrchestrator (bare
        // ensureClone) → the exit-128 propagates → boot() throws → assertNotNull is never reached → this test reds.
        val git = CloneFailsGit()
        val booted = BootOrchestrator(
            config(RepoConfig("git@github.com:org/does-not-exist.git", "main")), secrets(),
            WorktreeManager(git, gitRoot()), FakeSpawner(), scope,
        ).boot() // must NOT throw
        assertNotNull(booted, "boot survives an uncloneable repo (degraded), never aborts")
        assertTrue(booted.state.channels.isNotEmpty(), "hub is up despite the clone failure")
        assertTrue(git.cloneAttempts() > 0, "a configured repo IS attempted (unlike the placeholder)")
    }
}
