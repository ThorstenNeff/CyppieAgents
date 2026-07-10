package com.tneff.cyppieagents.pty

import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * CYP-332 — the **PTY manager** (02 §10; reverses 05-D4 piped-stdio). One real **pty4j** pseudo-terminal per
 * agent, running an **interactive `claude` TUI** (NOT `--print`/stream-json). It is the server foundation for
 * the Desktop JediTerm terminal (CYP-331 Option D): the [com.tneff.cyppieagents.routing.terminalSocket]
 * route pumps [TerminalInput]/[TerminalResize] into a [PtyHandle] and streams [PtyHandle] output/exit back.
 *
 * **Single-flight per session (design-doc §4.1):** at most **one live PTY per agent** — [open] rejects
 * (`PtyBusyException`) while one is live, so there are never two rival interactive processes on one session.
 * (The mode hand-off mediated↔interactive and PTY-survives-reconnect are follow-up stories; this manager
 * only guarantees the invariant.)
 *
 * **Launch-mode seam (CYP-348):** the launch [command] is the shared, boot-configured seam. The default is
 * set by `BootOrchestrator` — the CYP-333 interim terminal is a **`bash` login-shell in the worktree**
 * (Auftraggeber 2026-07-10: NO second interactive `claude` beside the mediated session — two auto-approving
 * agents in one worktree = edit-conflict risk). The BE-2 hand-off (CYP-355) reuses the same seam via the
 * per-open [open] `command` override to spawn `claude --resume <sid>` for the SAME session. Also injectable
 * for tests (a fake TUI) and the full-boot E2E — the teeth exercise a REAL pty4j PTY end-to-end (spawn → I/O
 * → resize → exit), not a mock (mirrors the real-`ProcessBuilderSpawner` approach of the resume tests).
 */
class PtyManager(
    /** cwd for the agent's PTY = its git worktree (Spec §11). */
    private val worktreeDirOf: (agentId: String) -> File,
    /** ANTHROPIC_API_KEY for the spawn (store override → env fallback, CYP-96), or null. */
    private val resolveApiKey: (agentId: String) -> String?,
    private val scope: CoroutineScope,
    /** Default launch command. **CYP-361 fail-closed default = `["bash","-l"]`** (the CYP-348 interim
     *  worktree-shell), NOT `claude`: a construction site that forgets to pass a command must spawn a plain
     *  shell, never a SECOND auto-approving `claude` in the worktree (CYP-321 skip-permissions → edit-conflict
     *  vector once `/ws/terminal` goes live). Boot passes it explicitly; a single [open] may override it
     *  (BE-2: `claude --resume <sid>`); tests pass a fake TUI. */
    private val command: List<String> = listOf("bash", "-l"),
    /** Extra PATH-whitelisted env; TERM/HUB_AGENT_ID/PATH/API-key are added by [open]. */
    private val baseEnv: Map<String, String> = emptyMap(),
) {
    private val log = LoggerFactory.getLogger("pty")
    private val live = ConcurrentHashMap<String, PtyHandle>()

    /** True iff [agentId] currently has a live PTY (single-flight probe). */
    fun isLive(agentId: String): Boolean = live.containsKey(agentId)

    /**
     * Spawn the agent's interactive PTY and start streaming. [onOutput] receives raw stdout byte-runs;
     * [onExit] fires once with the exit code after the process ends (then the handle is auto-removed).
     * Throws [PtyBusyException] if a PTY is already live for [agentId] (single-flight).
     */
    fun open(
        agentId: String,
        cols: Int,
        rows: Int,
        onOutput: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
        /** Per-open launch override (CYP-348 seam). Default = the boot-configured [command] (bash interim);
         *  BE-2/CYP-355 passes `claude --resume <sid>` here for the hand-off, same session. */
        command: List<String> = this.command,
    ): PtyHandle {
        val cwd = worktreeDirOf(agentId)
        val env = buildMap {
            put("TERM", "xterm-256color")
            System.getenv("PATH")?.let { put("PATH", it) } // whitelist the host PATH so `claude` resolves
            putAll(baseEnv)
            put("HUB_AGENT_ID", agentId)
            resolveApiKey(agentId)?.let { put("ANTHROPIC_API_KEY", it) }
        }
        // Single-flight: claim the slot BEFORE spawning; a rival open sees the slot and is rejected. A
        // placeholder can't be used (no handle yet), so we build the handle then compareAndSet-style guard.
        val handle = PtyHandleImpl(agentId, cols.coerceAtLeast(1), rows.coerceAtLeast(1))
        if (live.putIfAbsent(agentId, handle) != null) {
            throw PtyBusyException(agentId)
        }
        try {
            handle.start(cwd, env, command, scope, onOutput) { code ->
                live.remove(agentId, handle)
                onExit(code)
            }
        } catch (e: Exception) {
            live.remove(agentId, handle) // spawn failed → free the slot, don't leak a dead claim
            throw e
        }
        log.info("pty spawned for agent={} ({}x{}) cwd={}", agentId, cols, rows, cwd)
        return handle
    }

    /** Tear the agent's PTY down (destroy + stop the reader), if any. Idempotent. */
    fun close(agentId: String) {
        live.remove(agentId)?.close()
    }
}

/** The control surface the WS route drives (input/resize) + teardown. */
interface PtyHandle {
    val agentId: String
    /** Write bytes to the PTY stdin (the terminal's keystrokes/paste). */
    fun write(bytes: ByteArray)
    /** Resize the PTY (raises SIGWINCH so the TUI reflows). */
    fun resize(cols: Int, rows: Int)
    /** Destroy the process and stop streaming. Idempotent. */
    fun close()
}

/** Thrown by [PtyManager.open] when a PTY is already live for the agent (single-flight §4.1). */
class PtyBusyException(agentId: String) :
    IllegalStateException("agent '$agentId' already has a live interactive PTY")

private class PtyHandleImpl(
    override val agentId: String,
    private var cols: Int,
    private var rows: Int,
) : PtyHandle {
    private val log = LoggerFactory.getLogger("pty.handle")
    @Volatile private var process: PtyProcess? = null
    @Volatile private var closed = false

    fun start(
        cwd: File,
        env: Map<String, String>,
        command: List<String>,
        scope: CoroutineScope,
        onOutput: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
    ) {
        val proc = PtyProcessBuilder(command.toTypedArray())
            .setEnvironment(env)
            .setDirectory(cwd.absolutePath)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .start()
        process = proc
        // One IO coroutine pumps stdout→onOutput until EOF, then waits for the exit code and reports it once.
        scope.launch(Dispatchers.IO) {
            val stdout = proc.inputStream
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = stdout.read(buf) // blocking; returns -1 at EOF (process closed its PTY)
                    if (n < 0) break
                    if (n > 0) onOutput(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (!closed) log.debug("pty read ended for agent={}: {}", agentId, e.message)
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            onExit(code)
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed) return
        val out = process?.outputStream ?: return
        runCatching { out.write(bytes); out.flush() }
            .onFailure { log.debug("pty write failed for agent={}: {}", agentId, it.message) }
    }

    override fun resize(cols: Int, rows: Int) {
        if (closed) return
        this.cols = cols.coerceAtLeast(1); this.rows = rows.coerceAtLeast(1)
        runCatching { process?.winSize = WinSize(this.cols, this.rows) }
            .onFailure { log.debug("pty resize failed for agent={}: {}", agentId, it.message) }
    }

    override fun close() {
        if (closed) return
        closed = true
        // destroy() → the PTY's stdout EOFs → the pump loop ends → `waitFor` → onExit fires (the SINGLE
        // source of "process gone"). We deliberately do NOT cancel [pumpJob] here — cancelling would race
        // out that final onExit. The pump completes on its own once the destroyed process closes the PTY.
        runCatching { process?.destroy() }
    }
}
