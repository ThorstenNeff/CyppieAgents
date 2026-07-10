package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.HubConfig
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.pty.PtyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-348 — the terminal **launch-mode seam** threaded `BootOrchestrator → PtyManager`. The CYP-333 interim
 * terminal is a **`bash` login-shell in the agent's worktree** (Auftraggeber 2026-07-10: NOT a second
 * interactive `claude` — two auto-approving agents in one worktree = edit-conflict risk). The same seam
 * carries `claude --resume <sid>` per-open for the BE-2 hand-off (CYP-355).
 *
 * Teeth drive a **REAL pty4j PTY through the real boot wiring** (`booted.ptyManager`), the strongest form —
 * a mock proves nothing about the seam. Agent sessions boot on a fake spawner (no real `claude`).
 */
class Cyp348TerminalLaunchModeTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @AfterTest fun tearDown() = scope.cancel()

    /** Boot agents get a fake process (no real `claude`); the terminal PTY is what we exercise. */
    private class NoopSpawner : ProcessSpawner {
        override fun spawn(command: List<String>, cwd: File, env: Map<String, String>) = object : AgentProcess {
            override val stdoutLines = emptyFlow<String>()
            override suspend fun writeLine(line: String) {}
            override fun destroy() {}
        }
    }

    private fun config() = PlatformConfig(
        repo = RepoConfig("git@github.com:org/repo.git", "main"),
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

    private fun boot(launch: List<String>?) = BootOrchestrator(
        config(), secrets(), WorktreeManager(FakeGit(), Files.createTempDirectory("cyp348").toFile()),
        NoopSpawner(), scope, terminalLaunchCommand = launch,
    ).boot()

    private class Sink {
        private val sb = StringBuilder()
        val onOutput: (ByteArray) -> Unit = { b -> synchronized(sb) { sb.append(String(b)) } }
        fun text() = synchronized(sb) { sb.toString() }
    }

    private suspend fun awaitContains(sink: Sink, needle: String, ms: Long = 20_000) =
        withTimeout(ms) { while (!sink.text().contains(needle)) delay(50) }

    @Test
    fun bootDefault_terminalSpawnsBashShell_notClaude() = runBlocking {
        // No launch override → prod default = `bash -l`. Prove it is a real shell (evaluates `$((...))`),
        // which `claude` (the PtyManager fallback default) would never do — so the boot seam DID override it.
        val booted = boot(launch = null)
        val sink = Sink()
        val handle = booted.ptyManager.open("backend", 80, 24, onOutput = sink.onOutput, onExit = {})
        handle.write("echo CYP348-\$((6*7))\n".toByteArray())
        awaitContains(sink, "CYP348-42") // bash arithmetic expansion → 42; a literal echo (cat/claude) would not
        assertTrue(sink.text().contains("CYP348-42"),
            "the boot-default terminal launch is a bash shell (evaluated \$((6*7))=42), not claude: <${sink.text().takeLast(120)}>")
        booted.ptyManager.close("backend")
    }

    @Test
    fun terminalLaunchCommand_isThreadedFromBoot_intoThePty() = runBlocking {
        // Inject a fake command at BOOT and prove it reaches the PTY spawn — the seam Tester's full-boot E2E
        // rides. Mutation: if boot hardcoded/ignored the param, the fake never runs → no GOT:.
        val fake = Files.createTempFile("cyp348-fake", ".sh").toFile().apply {
            writeText("#!/usr/bin/env bash\nwhile IFS= read -r l; do printf 'GOT:%s\\n' \"\$l\"; done\n"); setExecutable(true)
        }
        val booted = boot(launch = listOf(fake.absolutePath))
        val sink = Sink()
        val handle = booted.ptyManager.open("backend", 80, 24, onOutput = sink.onOutput, onExit = {})
        handle.write("ping\n".toByteArray())
        awaitContains(sink, "GOT:ping")
        assertTrue(sink.text().contains("GOT:ping"),
            "the boot-injected launch command reached the real PTY spawn (BootOrchestrator→PtyManager seam)")
        booted.ptyManager.close("backend")
    }

    @Test
    fun open_perCallCommand_overridesTheBootDefault() = runBlocking {
        // The BE-2 (CYP-355) seam: a single open() may override the boot default (e.g. `claude --resume <sid>`).
        val dflt = Files.createTempFile("cyp348-dflt", ".sh").toFile().apply {
            writeText("#!/usr/bin/env bash\nwhile IFS= read -r l; do printf 'DFLT:%s\\n' \"\$l\"; done\n"); setExecutable(true)
        }
        val override = Files.createTempFile("cyp348-ovr", ".sh").toFile().apply {
            writeText("#!/usr/bin/env bash\nwhile IFS= read -r l; do printf 'OVR:%s\\n' \"\$l\"; done\n"); setExecutable(true)
        }
        val mgr = PtyManager(
            worktreeDirOf = { Files.createTempDirectory("cyp348-ovr-wt").toFile() },
            resolveApiKey = { null }, scope = scope, command = listOf(dflt.absolutePath),
        )
        val sink = Sink()
        val handle = mgr.open("backend", 80, 24, onOutput = sink.onOutput, onExit = {}, command = listOf(override.absolutePath))
        handle.write("x\n".toByteArray())
        awaitContains(sink, "OVR:x")
        assertTrue(sink.text().contains("OVR:x") && !sink.text().contains("DFLT:"),
            "open(command=…) ran the per-open override, not the constructor default (the BE-2 --resume seam)")
        mgr.close("backend")
    }
}
