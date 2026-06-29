package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.ProcessCommandRunner
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.ProcessBuilderSpawner
import com.tneff.cyppieagents.model.Role
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * RB1 / CYP-110 — Real-Agent E2E (Tier B) harness. I build/wire it; the Tester drives the journey + evidence.
 *
 * It boots the **production** path (real `claude` spawner + real git) against a **fresh throwaway sandbox
 * repo** (NEVER the product repo) with a PO + one Worker, so a small real task runs end-to-end over the
 * **real mediation path** (PO decomposes → worker in its worktree → commit/push to the sandbox → status).
 *
 * **Hard safety rules (Doc 05 / [[auth-credentials-policy]]):**
 *  - **No autonomous / env / self-set key.** The harness never *invents* a key, and [requireNoApiKeyInEnv]
 *    fails closed if `ANTHROPIC_API_KEY` is in the env. The key, **if any**, is supplied **out-of-band by
 *    the human**: CYP-110 creds-go uses Option ii — a single-run read of `ANTHROPIC_API_KEY` from the
 *    human's **gitignored `local.properties`** ([readSubscriptionKeyFromLocalProperties]) into [Secrets]
 *    in-memory only (never persisted to the CYP-96 store, never logged/committed; masked via SecretMasker
 *    at every event egress as usual). Absent → `claude` falls back to its subscription OAuth login
 *    (`~/.claude`) / the CYP-96 store. If headless can't authenticate either way → it aborts and reports.
 *  - **CLI pinned** ([ConnectorDefaults.PINNED_CLI_VERSION]); [assertPinnedClaudeCli] checks the runtime.
 *  - **RUN_RB1=1-gated** ([rb1Enabled]); never in the default gate. The single quota-aware live run fires
 *    only on the human/PO creds-go — no autonomous live run.
 */
object Rb1RealAgentHarness {

    /** True only when the operator has explicitly opted into the (quota-consuming) real run. */
    fun rb1Enabled(): Boolean = System.getenv("RUN_RB1") == "1"

    /**
     * Fail-closed: RB1 runs on subscription OAuth, never an API key. If `ANTHROPIC_API_KEY` is present we
     * abort rather than risk an unintended billed/keyed run — the harness must not set or consume a key.
     */
    fun requireNoApiKeyInEnv() {
        val key = System.getenv("ANTHROPIC_API_KEY")
        check(key.isNullOrBlank()) {
            "RB1 must run on subscription OAuth only — ANTHROPIC_API_KEY is set; aborting (the harness " +
                "never injects or consumes a key). Unset it and ensure `claude` is logged in (~/.claude)."
        }
    }

    /**
     * The one-run subscription key bridge (CYP-110 creds-go, **Option ii**): read `ANTHROPIC_API_KEY` from
     * the **gitignored `local.properties`** the human placed out-of-band (operator-authorized for this run).
     * Walks UP from the test cwd so a worktree run finds the MAIN checkout's `local.properties` (the key is
     * working-dir-local, NOT shared across worktrees). Returns null if absent → the caller stays fail-closed
     * (OAuth / CYP-96 store). **Never logs or returns the value anywhere but into [Secrets]; never persisted.**
     */
    fun readSubscriptionKeyFromLocalProperties(): String? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val lp = File(dir, "local.properties")
            if (lp.isFile) {
                val key = lp.readLines()
                    .firstOrNull { it.trimStart().startsWith("ANTHROPIC_API_KEY") && it.contains('=') }
                    ?.substringAfter('=')?.trim()
                    ?.takeIf { it.isNotBlank() }
                if (key != null) return key
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Assert the installed `claude` matches the pinned CLI; returns the reported version line for evidence. */
    fun assertPinnedClaudeCli(): String {
        val out = ProcessBuilder("claude", "--version")
            .redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
        check(out.contains(ConnectorDefaults.PINNED_CLI_VERSION)) {
            "RB1 expects pinned claude ${ConnectorDefaults.PINNED_CLI_VERSION}, but runtime reports: '$out'"
        }
        return out
    }

    /**
     * Create a throwaway sandbox git repo at [dir] (NOT the product repo) with one seed commit, and return
     * its `file://` URL for `repo.url`. Uses real git — the worker will clone this and push back to it.
     */
    fun initSandboxRepo(dir: File): String {
        dir.mkdirs()
        val git = ProcessCommandRunner()
        git.run(listOf("git", "init", "--initial-branch=main"), dir)
        git.run(listOf("git", "config", "user.email", "rb1@sandbox.local"), dir)
        git.run(listOf("git", "config", "user.name", "RB1 Sandbox"), dir)
        // Accept pushes to the checked-out branch (a non-bare sandbox the worker can push to).
        git.run(listOf("git", "config", "receive.denyCurrentBranch", "updateInstead"), dir)
        File(dir, "README.md").writeText("# RB1 sandbox — throwaway, NOT a product repo\n")
        git.run(listOf("git", "add", "."), dir)
        git.run(listOf("git", "commit", "-m", "RB1: seed sandbox"), dir)
        return dir.toURI().toString()
    }

    /** The RB1 platform config: PO + one Worker, both real `claude`, pointed at the sandbox [repoUrl]. */
    fun sandboxConfig(repoUrl: String) = PlatformConfig(
        repo = RepoConfig(repoUrl, "main"),
        agents = listOf(
            AgentConfig("po", "Product Owner", Role.PO, launch = "claude"),
            AgentConfig("backend", "Backend", Role.WORKER, launch = "claude"),
        ),
    )

    /**
     * Boot the REAL platform (real `claude` spawner + real git) against the sandbox. The harness wires NO
     * key into [Secrets] (apiKey=null) — the connector lazy-resolves a key from the CYP-96 store
     * (`project-config.json`, operator/human-set, masked, per-project) if present, else runs on OAuth.
     * Caller is responsible for [rb1Enabled]/[requireNoApiKeyInEnv]/[assertPinnedClaudeCli] gating first.
     */
    fun bootRealAgentPlatform(gitRoot: File, repoUrl: String, scope: CoroutineScope): BootedPlatform {
        requireNoApiKeyInEnv() // the key path is local.properties (out-of-band), never the env
        val config = sandboxConfig(repoUrl)
        // CYP-110 creds-go (Option ii): the one-run subscription key comes from the human's gitignored
        // local.properties (never persisted/logged/committed). Absent → null → OAuth / CYP-96 store fallback.
        val key = readSubscriptionKeyFromLocalProperties()
        println("RB1: subscription key ${if (key != null) "present (masked) — one-run only" else "absent → OAuth/store fallback"}")
        val secrets = Secrets(
            agentTokens = config.agents.associate { "tok-${it.id}" to it.id },
            operatorToken = "tok-operator",
            apiKey = key, // in-memory only → connector injects at spawn; NOT written to the CYP-96 store
        )
        val worktrees = WorktreeManager(ProcessCommandRunner(), gitRoot, config.projectId)
        return BootOrchestrator(
            config = config,
            secrets = secrets,
            worktrees = worktrees,
            spawner = ProcessBuilderSpawner(), // real `claude` spawn (env-whitelisted, stderr discarded)
            scope = scope,
            projectConfigFile = gitRoot.toPath().resolve("project-config.json").toFile(),
            projectRegistryFile = gitRoot.toPath().resolve("projects.json").toFile(),
            channelShareFile = gitRoot.toPath().resolve("channel-shares.json").toFile(),
        ).boot()
    }
}
