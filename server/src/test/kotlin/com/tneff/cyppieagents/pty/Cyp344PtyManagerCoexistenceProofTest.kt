package com.tneff.cyppieagents.pty

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-344 — coexistence proof **through the real production seam** ([PtyManager.open], pty4j, real
 * interactive `claude --resume` TUI — TERM=xterm-256color + initial WinSize, NO `--print`). Answers the
 * Reviewer's false-negative hardening (FN-1..5): the danger is concluding "interactive doesn't persist"
 * when it is merely buffered / written elsewhere.
 *
 * Flow: mediated create (headless `claude -p`, plant MED needle, capture sid) → **interactive turn via the
 * real PtyManager** (recall MED [FN-1 liveness+context], plant INT needle) → graceful exit → scan the WHOLE
 * `~/.claude` tree by **file content** for the INT needle (FN-3 "nowhere vs elsewhere" + FN-4 file-content
 * oracle) after settle+sync (FN-2) → mediated `--resume` recall of BOTH (secondary).
 *
 * REAL-CLAUDE + REAL-PTY + BILLED → gated behind `RUN_CYP344=1`; never runs in the normal gate.
 * CLI pinned 2.1.206 (FN-5). This spike changes NO production code.
 */
class Cyp344PtyManagerCoexistenceProofTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    private val enabled = System.getenv("RUN_CYP344") == "1"
    private val home = File(System.getProperty("user.home"))
    private val projects = File(home, ".claude/projects")
    private val DENY = listOf(
        "--disallowedTools", "Write", "Edit", "MultiEdit", "NotebookEdit", "Bash",
        "Read", "Glob", "Grep", "WebFetch", "WebSearch", "Task", "TodoWrite",
    )

    /** Headless mediated turn; returns (sessionId, assistantText). Strips nested CLAUDE_CODE_* for hygiene. */
    private fun mediated(cwd: File, prompt: String, resumeSid: String?): Pair<String?, String> {
        val cmd = mutableListOf("claude")
        if (resumeSid != null) cmd += listOf("--resume", resumeSid)
        cmd += listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose") + DENY
        val pb = ProcessBuilder(cmd).directory(cwd).redirectErrorStream(false)
        pb.environment().keys.removeIf { it.startsWith("CLAUDE_CODE") || it == "CLAUDECODE" || it == "CLAUDE_EFFORT" }
        val p = pb.start()
        val user = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":${jsonStr(prompt)}}]}}""" + "\n"
        p.outputStream.write(user.toByteArray()); p.outputStream.flush(); p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        var sid: String? = null; val asst = StringBuilder()
        out.lineSequence().forEach { line ->
            val l = line.trim(); if (l.isEmpty()) return@forEach
            if (l.contains("\"session_id\"")) Regex("\"session_id\"\\s*:\\s*\"([^\"]+)\"").find(l)?.let { sid = it.groupValues[1] }
            if (l.contains("\"result\"")) Regex("\"result\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(l)?.let { asst.append(it.groupValues[1]) }
        }
        return sid to asst.toString()
    }

    private fun jsonStr(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun transcriptOf(sid: String): File? =
        projects.walkTopDown().firstOrNull { it.name == "$sid.jsonl" }
    private fun treeFilesContaining(needle: String): List<File> =
        projects.walkTopDown().filter { it.isFile && it.extension == "jsonl" }
            .filter { runCatching { it.readText().contains(needle) }.getOrDefault(false) }.toList()

    @Test
    fun coexistence_through_real_PtyManager_seam() = runBlocking {
        if (!enabled) { println("[cyp344] RUN_CYP344!=1 — skipping billed real-seam proof"); return@runBlocking }
        val cwd = Files.createTempDirectory("cyp344-pty").toFile()
        val stamp = System.getProperty("cyp344.stamp") ?: "s"
        val MED = "MEDNEEDLE-$stamp"; val INT = "INTNEEDLE-$stamp"
        println("[cyp344] cwd=$cwd HOME=$home CONFIG_DIR=${System.getenv("CLAUDE_CONFIG_DIR") ?: "(unset)"}")

        // ---- Phase 1: mediated create ----
        val (sid, _) = mediated(cwd, "Answer from conversation only; no tools. Remember codeword $MED. Reply only OK.", null)
        assertTrue(sid != null, "mediated create produced a session id")
        val tpath = transcriptOf(sid!!); println("[cyp344] sid=$sid transcript=$tpath")
        assertTrue(tpath != null && tpath.exists(), "the mediated transcript <sid>.jsonl exists (cwd-derived path)")

        // ---- Phase 2: interactive turn through the REAL PtyManager (pty4j, TERM+WinSize, real TUI argv) ----
        val out = StringBuilder()
        val exited = CompletableDeferred<Int>()
        val mgr = PtyManager(
            worktreeDirOf = { cwd },
            resolveApiKey = { null },
            scope = scope,
            command = listOf("claude", "--resume", sid, "--dangerously-skip-permissions"),
        )
        val handle = mgr.open("agent", 120, 40,
            onOutput = { b -> synchronized(out) { out.append(String(b)) } },
            onExit = { c -> exited.complete(c) })
        fun seen(vararg n: String) = synchronized(out) { n.any { out.contains(it) } }
        suspend fun await(vararg n: String, ms: Long) = withTimeout(ms) { while (!seen(*n)) delay(200) }

        // trust dialog (single contiguous word) then composer-ready
        await("trust", "bypass", ms = 25_000)
        if (seen("trust") && !seen("bypass")) { delay(1000); handle.write("\r".toByteArray()); await("bypass", ms = 25_000) }
        delay(4000)
        // FN-1 liveness + context: ask the live TUI to recall the mediated needle + plant the interactive one
        handle.write("Two things, short: (1) what is codeword $MED? (2) also remember codeword $INT.".toByteArray())
        delay(1500); handle.write("\r".toByteArray())
        await(MED, ms = 90_000)  // the interactive TUI answered with the mediated needle => live AND has context
        println("[cyp344] FN-1 interactive liveness+context: TUI recalled $MED = true")
        delay(3000)
        // graceful exit
        handle.write("/exit".toByteArray()); handle.write("\r".toByteArray())
        val code = runCatching { withTimeout(30_000) { exited.await() } }.getOrElse { handle.close(); -1 }
        println("[cyp344] interactive exit code=$code")

        // ---- FN-2 settle + fs sync, then FN-3/FN-4: scan the WHOLE tree by file content ----
        delay(6000); runCatching { ProcessBuilder("sync").start().waitFor() }
        val hits = treeFilesContaining(INT)
        println("[cyp344] FN-3/4 files anywhere under ~/.claude/projects containing $INT: ${hits.map { it.name }}")
        assertTrue(hits.any { it.name == "$sid.jsonl" },
            "the interactive turn PERSISTED to the SAME <sid>.jsonl (real-seam, file-content oracle) — not nowhere, not a fork")

        // ---- FN-4 secondary: mediated --resume recall of BOTH needles ----
        val (rsid, recall) = mediated(cwd, "Answer from conversation only; no tools. List every codeword you know, space-separated.", sid)
        println("[cyp344] mediated hand-back recall: sid=$rsid answer=${recall.take(120)}")
        assertTrue(rsid == sid, "hand-back re-resume kept the same sid (no fork)")
        assertTrue(recall.contains(MED) && recall.contains(INT),
            "the mediated reader recalls BOTH the mediated AND the interactive needle after hand-back => coexistence holds through the real seam")
        println("[cyp344] VERDICT via real PtyManager seam: COEXISTENCE HOLDS")
    }
}
