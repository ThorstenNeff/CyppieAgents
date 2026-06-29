package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.ProcessCommandRunner
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.events.EventFilter
import com.tneff.cyppieagents.events.Page
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RB1 / CYP-110 — Real-Agent S8 Durchstich JOURNEY (Tier B), driven on [Rb1RealAgentHarness].
 *
 * **RUN_RB1=1-gated, inert otherwise** (the default `:e2e:test` early-returns → spawns NO `claude`, burns
 * NO quota). The single quota-aware live run fires ONLY when the operator sets `RUN_RB1=1` on the explicit
 * human/PO creds-go, with a logged-in subscription and **no `ANTHROPIC_API_KEY`** ([[auth-credentials-policy]];
 * the harness fails closed via [Rb1RealAgentHarness.requireNoApiKeyInEnv]). This journey injects NO key.
 *
 * It injects ONE small deterministic task to the PO and OBSERVES the real loop over the real mediation path:
 * **PO decomposes → worker works in its worktree → commit/push to the throwaway sandbox → status to PO**.
 * Conservative guard (PO §6): ~10 min hard cap, abort on deadline (no `--resume` retry), evidence ALWAYS
 * captured (full on success, partial on timeout). Asserts the S8 invariants A1–A5; captures masked evidence.
 *
 * NOTE (honest scope): behavioural validation happens at the gated real run — here it is compile-/logic-
 * verified and inert. Real agents are nondeterministic; assertions are at the S8-invariant level with
 * generous polling + a clean abort, never on brittle internal timing.
 */
class Rb1RealAgentJourneyTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val git = ProcessCommandRunner()
    private val operator = HubState.OPERATOR_ID
    private val spoke = "po-backend"
    private val proofMarker = "RB1: durchstich proof" // the worker's commit message marker
    private val capMillis = 10 * 60 * 1000L            // PO §6: ~10 min hard cap (quota guard)
    private val pollMillis = 5_000L

    @Test
    fun rb1_s8Durchstich_realAgentLoop_overRealMediation() = runBlocking {
        if (!Rb1RealAgentHarness.rb1Enabled()) return@runBlocking // default gate: inert, no spawn, no quota

        // P1/P2 — fail-closed creds + pinned CLI (subscription OAuth only; aborts if a key is in env).
        val cliVersion = Rb1RealAgentHarness.assertPinnedClaudeCli()
        Rb1RealAgentHarness.requireNoApiKeyInEnv()

        val gitRoot = Files.createTempDirectory("rb1-gitroot").toFile()
        val sandbox = Files.createTempDirectory("rb1-sandbox").toFile()
        val repoUrl = Rb1RealAgentHarness.initSandboxRepo(sandbox)
        val booted = Rb1RealAgentHarness.bootRealAgentPlatform(gitRoot, repoUrl, scope)
        val evidence = (System.getenv("RB1_EVIDENCE_DIR")?.let { File(it) }
            ?: Files.createTempDirectory("rb1-evidence").toFile()).apply { mkdirs() }
        println("RB1 evidence dir: $evidence  (copy to test/qa-artifacts/rb1-<date>/ after the run)")

        try {
            // A4 precondition: both REAL claude sessions are live over the real mediation path.
            assertTrue("po" in booted.bootedAgents && "backend" in booted.bootedAgents,
                "both real claude sessions booted (po+backend); got ${booted.bootedAgents}")

            // Seed: inject ONE small, crisp task to the PO as a user-message turn (the human/operator turn).
            booted.connectorSessions.session("po")?.sendTurn(UserTurn(seedTask()))
                ?: error("no PO session to inject the task into")

            // Observe the real loop unfold; conservative ~10 min cap, abort on deadline (no retry).
            val deadline = System.currentTimeMillis() + capMillis
            var done = false
            while (System.currentTimeMillis() < deadline && !done) {
                done = sandboxHasProof(sandbox) && statusReported(booted)
                if (!done) delay(pollMillis)
            }

            captureEvidence(evidence, booted, gitRoot, sandbox, cliVersion)

            // --- S8 acceptance (A1–A5), asserted on what the real loop produced ---
            assertTrue(done, "A1/A3: S8 durchstich did not complete within ~10min (worker push to sandbox + " +
                "status to PO). Partial evidence in $evidence")
            // A3: the result is on the throwaway sandbox remote (the worker pushed its commit).
            assertTrue(sandboxHasProof(sandbox), "A3: proof commit reached the sandbox remote")
            // A2: the commit lives in the WORKER's own worktree (not elsewhere).
            assertTrue(workerWorktreeHasProof(gitRoot, booted.activeProjectId),
                "A2: proof commit is in the worker's own worktree")
            // A4: real mediation logged agent-attributed events for BOTH agents (real path, not fake).
            val events = booted.eventSink.query(EventFilter.ALL, Page(limit = 5000)).events
            assertTrue(events.any { it.agentId == "po" } && events.any { it.agentId == "backend" },
                "A4: real mediation logged events for both po and backend")
            // A1: coordination went over the hub spoke (a status message from the worker reached the PO).
            assertTrue(statusReported(booted), "A1: worker status reached the PO over the hub ($spoke)")
            // A5: no credential material leaked into the captured evidence (event-log/timeline egress).
            assertNoCredentialNeedle(evidence)
        } finally {
            booted.bootedAgents.forEach { booted.connectorSessions.remove(it) }
            // Teardown (Reviewer note #2, hygiene): the throwaway gitRoot + sandbox are temp dirs — clean them
            // up so the run leaves no /tmp leak. Evidence lives in a SEPARATE dir and is preserved.
            runCatching { gitRoot.deleteRecursively() }
            runCatching { sandbox.deleteRecursively() }
        }
    }

    private fun seedTask(): String = """
        Koordiniere bitte eine winzige Aufgabe mit dem Backend-Worker ausschliesslich ueber den Hub:
        Lege im Repo die Datei rb1-proof.md mit genau der einen Zeile "RB1 durchstich OK" an, committe sie
        auf einem Branch feature/RB1-proof mit der Commit-Message "$proofMarker", pushe den Branch zum
        Remote, und melde mir danach kurz den Status. Halte es minimal; keine weiteren Aenderungen.
    """.trimIndent()

    // --- success-condition reads (real git + real hub) ---

    private fun sandboxHasProof(sandbox: File): Boolean {
        val log = git.run(listOf("git", "log", "--all", "--oneline"), sandbox)
        return log.exitCode == 0 && log.output.contains(proofMarker)
    }

    /** The worker's worktree lives under [gitRoot]/projects/<projectId>/<name>; locate the one with the proof. */
    private fun workerWorktreeHasProof(gitRoot: File, projectId: String): Boolean {
        val parent = File(gitRoot, "projects/$projectId")
        val dirs = parent.listFiles()?.filter { File(it, ".git").exists() || File(it, ".git").isFile } ?: return false
        // Prefer the worker's id-named worktree; fall back to any worktree carrying the proof.
        val candidate = dirs.firstOrNull { it.name == "backend" } ?: dirs.firstOrNull { worktreeHasProof(it) }
        return candidate != null && worktreeHasProof(candidate)
    }

    private fun worktreeHasProof(worktree: File): Boolean {
        val log = git.run(listOf("git", "log", "--all", "--oneline"), worktree)
        return log.exitCode == 0 && log.output.contains(proofMarker)
    }

    private fun statusReported(booted: BootedPlatform): Boolean =
        booted.hub.channelMessages(operator, spoke).any { it.from == "backend" }

    // --- evidence capture (masked; the hub/projector already keep bodies masked + details content-free) ---

    private suspend fun captureEvidence(out: File, booted: BootedPlatform, gitRoot: File, sandbox: File, cliVersion: String) {
        val events = booted.eventSink.query(EventFilter.ALL, Page(limit = 5000)).events
        File(out, "loop-sequence.tsv").writeText(
            "seq\tts\tagentId\ttype\n" + events.joinToString("\n") { "${it.seq}\t${it.ts}\t${it.agentId}\t${it.type}" },
        )
        File(out, "timeline-$spoke.txt").writeText(
            booted.hub.channelMessages(operator, spoke).joinToString("\n") { "[${it.ts}] ${it.from}: ${it.body}" },
        )
        File(out, "sandbox-gitlog.txt").writeText(git.run(listOf("git", "log", "--all", "--stat"), sandbox).output)
        File(out, "worker-worktrees.txt").writeText(
            (File(gitRoot, "projects/${booted.activeProjectId}").listFiles()?.joinToString("\n") { dir ->
                "${dir.name}: " + git.run(listOf("git", "log", "--all", "--oneline"), dir).output.replace("\n", " | ")
            } ?: "(no worktrees dir)"),
        )
        File(out, "creds-and-pin.txt").writeText(
            "claude --version: $cliVersion\n" +
                // Auth direction is structural (CYP-110 harness guard): default = subscription/OAuth, key ignored.
                "auth mode (RB1_AUTH): " + (if (Rb1RealAgentHarness.apiKeyModeSelected())
                    "apikey (Option ii — explicit, one-run bridge)" else "subscription/OAuth (default, fail-closed; any local.properties key IGNORED)") + "\n" +
                "ANTHROPIC_API_KEY in env: ${if (System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) "<empty, as required>" else "SET — UNEXPECTED (harness fails closed)"}\n",
        )
    }

    private fun assertNoCredentialNeedle(out: File) {
        // NON-VACUITY PRE-GUARD (Reviewer REQUIRED — J8/J9 discipline). The needle scan only means something
        // if the evidence actually carries REAL loop content; an empty/header-only capture would pass A5
        // vacuously = false safety exactly when a key flows (RB1_AUTH=apikey). So assert real content FIRST.
        val loopSeq = File(out, "loop-sequence.tsv").let { if (it.exists()) it.readText() else "" }
        assertTrue(loopSeq.contains("\tpo\t") && loopSeq.contains("\tbackend\t"),
            "A5 non-vacuity: evidence must carry real loop content (both agents present in loop-sequence.tsv) " +
                "before the credential scan is meaningful — refusing to pass A5 over an empty capture")

        // The actual A5 check: no credential material in any captured evidence file (creds-and-pin.txt is the
        // one file that legitimately names the env var, so it is excluded from the scan).
        val needle = Regex("""sk-ant-[A-Za-z0-9_-]{8,}|ANTHROPIC_API_KEY=|Bearer [A-Za-z0-9._-]{12,}|oauth[_-]?token""", RegexOption.IGNORE_CASE)
        val offenders = out.walkTopDown().filter { it.isFile && it.name != "creds-and-pin.txt" }
            .filter { needle.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue(offenders.isEmpty(), "A5: credential needle leaked into evidence files: $offenders")
    }
}
