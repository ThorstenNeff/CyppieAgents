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
    /** Extra PATH-whitelisted env; TERM/HUB_AGENT_ID/PATH/API-key are added by the spawn. */
    private val baseEnv: Map<String, String> = emptyMap(),
    /** CYP-355 — bounded scrollback replayed to a late [attach] so a viewer/E2E sees the session's recent
     *  output (the `--resume` history), not only bytes that arrive after it connects. */
    private val replayBufferBytes: Int = 64 * 1024,
) {
    private val log = LoggerFactory.getLogger("pty")
    private val live = ConcurrentHashMap<String, PtyHandleImpl>()

    /** True iff [agentId] currently has a live PTY (single-flight probe / [terminalSocket] attach-vs-spawn). */
    fun isLive(agentId: String): Boolean = live.containsKey(agentId)

    /**
     * CYP-355 — **spawn + own** the agent's interactive PTY (the hand-off motor's seam), with **no viewer
     * required**. The process outlives any single terminal window: viewers [attach]/detach independently, and
     * the motor tears it down at hand-back. [onExit] fires once after the process ends (then the slot frees).
     * Throws [PtyBusyException] if one is already live (single-flight — never two rival interactive processes).
     */
    fun spawnInteractive(
        agentId: String,
        cols: Int,
        rows: Int,
        command: List<String>,
        onExit: (Int) -> Unit,
    ): PtyHandle = spawn(agentId, cols, rows, command, initialOutput = null, onExit)

    /**
     * CYP-332 bash-interim path (unchanged signature): spawn the PTY and stream to the single [onOutput]. Kept
     * for the current `terminalSocket` spawn-on-connect; internally it is `spawnInteractive` + one [attach].
     */
    fun open(
        agentId: String,
        cols: Int,
        rows: Int,
        onOutput: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
        /** Per-open launch override (CYP-348 seam). Default = the boot-configured [command] (bash interim). */
        command: List<String> = this.command,
    ): PtyHandle = spawn(agentId, cols, rows, command, initialOutput = onOutput, onExit)

    private fun spawn(
        agentId: String,
        cols: Int,
        rows: Int,
        command: List<String>,
        initialOutput: ((ByteArray) -> Unit)?,
        onExit: (Int) -> Unit,
    ): PtyHandle {
        val cwd = worktreeDirOf(agentId)
        val env = buildMap {
            put("TERM", "xterm-256color")
            System.getenv("PATH")?.let { put("PATH", it) } // whitelist the host PATH so `claude` resolves
            putAll(baseEnv)
            put("HUB_AGENT_ID", agentId)
            resolveApiKey(agentId)?.let { put("ANTHROPIC_API_KEY", it) }
        }.filterKeys { !isNestedClaudeCodeVar(it) } // CYP-355 (b): strip nested-agent markers (see companion KDoc)
        // Single-flight: claim the slot BEFORE spawning; a rival spawn sees the slot and is rejected.
        val handle = PtyHandleImpl(agentId, cols.coerceAtLeast(1), rows.coerceAtLeast(1), replayBufferBytes)
        initialOutput?.let { handle.subscribe(it) } // subscribe BEFORE start so the first byte isn't missed
        if (live.putIfAbsent(agentId, handle) != null) {
            throw PtyBusyException(agentId)
        }
        try {
            handle.start(cwd, env, command, scope) { code ->
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

    /**
     * CYP-355 — **attach a viewer** to the agent's live PTY: replays the bounded scrollback, then streams live.
     * Returns null if no PTY is live (the caller falls back to spawn — the `terminalSocket` attach-vs-spawn
     * rule). [PtySubscription.close] detaches ONLY this viewer; the PTY lives on for the others and the motor.
     */
    fun attach(agentId: String, onOutput: (ByteArray) -> Unit): PtySubscription? =
        live[agentId]?.subscribe(onOutput)

    /** Write bytes to the live PTY's stdin (any viewer's keystrokes → the one process). No-op if not live. */
    fun write(agentId: String, bytes: ByteArray) {
        live[agentId]?.write(bytes)
    }

    /** Resize the live PTY. No-op if not live. */
    fun resize(agentId: String, cols: Int, rows: Int) {
        live[agentId]?.resize(cols, rows)
    }

    /** Tear the agent's PTY down (destroy + stop the reader), if any. Idempotent. */
    fun close(agentId: String) {
        live.remove(agentId)?.close()
    }

    companion object {
        /**
         * CYP-355 (b) — the interactive PTY's env is **clean by construction**: `PtyProcessBuilder.setEnvironment`
         * REPLACES the child env (it does not inherit the server's), so [open] builds a minimal whitelist. This
         * strip is the belt against that whitelist ever carrying a nested-agent marker: `CLAUDE_CODE_*` /
         * `CLAUDECODE`. Per the CYP-344 spike, a `claude` that inherits those from a PARENT claude session treats
         * itself as nested and **stops persisting its transcript** (changelog 2.1.170) — which would silently break
         * the resume-round-trip the whole hand-off rests on. Today [baseEnv] is empty, so nothing matches; the
         * strip makes the invariant survive a future [baseEnv] change instead of resting on "it happens to be empty".
         * Single-sourced here so the strip and its tooth read the same predicate.
         */
        fun isNestedClaudeCodeVar(key: String): Boolean = key == "CLAUDECODE" || key.startsWith("CLAUDE_CODE_")
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

/** CYP-355 — a single viewer's attachment to a live PTY; [close] detaches only this viewer, not the PTY. */
fun interface PtySubscription {
    fun close()
}

/** Thrown by [PtyManager.spawnInteractive]/[PtyManager.open] when a PTY is already live (single-flight §4.1). */
class PtyBusyException(agentId: String) :
    IllegalStateException("agent '$agentId' already has a live interactive PTY")

internal class PtyHandleImpl(
    override val agentId: String,
    private var cols: Int,
    private var rows: Int,
    private val replayBufferBytes: Int = 64 * 1024,
) : PtyHandle {
    private val log = LoggerFactory.getLogger("pty.handle")
    @Volatile private var process: PtyProcess? = null
    @Volatile private var closed = false

    // CYP-355 output fan-out. `bufferLock` makes buffer-append+subscriber-snapshot (pump) atomic against
    // replay+add (subscribe), so a chunk arriving during an attach is delivered EXACTLY once — never lost
    // between the replay and the add, never doubled by being both replayed and fanned out (see [subscribe]).
    private val bufferLock = Any()
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val replay = ArrayDeque<ByteArray>()
    private var replayBytes = 0

    fun start(
        cwd: File,
        env: Map<String, String>,
        command: List<String>,
        scope: CoroutineScope,
        onExit: (Int) -> Unit,
    ) {
        val proc = PtyProcessBuilder(command.toTypedArray())
            .setEnvironment(env)
            .setDirectory(cwd.absolutePath)
            .setInitialColumns(cols)
            .setInitialRows(rows)
            .start()
        process = proc
        // One IO coroutine pumps stdout → the fan-out until EOF, then waits for the exit code and reports once.
        scope.launch(Dispatchers.IO) {
            val stdout = proc.inputStream
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = stdout.read(buf) // blocking; returns -1 at EOF (process closed its PTY)
                    if (n < 0) break
                    if (n > 0) fanOut(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (!closed) log.debug("pty read ended for agent={}: {}", agentId, e.message)
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            onExit(code)
        }
    }

    /** Append to the bounded replay buffer and snapshot subscribers **atomically**, then deliver outside the
     *  lock (a subscriber callback must never re-enter [subscribe]). */
    private fun fanOut(chunk: ByteArray) {
        val targets: List<(ByteArray) -> Unit>
        synchronized(bufferLock) {
            replay.addLast(chunk)
            replayBytes += chunk.size
            while (replayBytes > replayBufferBytes && replay.size > 1) {
                replayBytes -= replay.removeFirst().size
            }
            targets = subscribers.toList()
        }
        targets.forEach { runCatching { it(chunk) }.onFailure { log.debug("pty subscriber failed for agent={}: {}", agentId, it.message) } }
    }

    /** Attach a viewer: replay the buffered scrollback, then add it — atomically, so it neither misses a chunk
     *  arriving mid-attach nor sees one twice (the [fanOut] lock is the counterpart). */
    fun subscribe(onOutput: (ByteArray) -> Unit): PtySubscription {
        synchronized(bufferLock) {
            replay.forEach { runCatching { onOutput(it) } }
            subscribers.add(onOutput)
        }
        return PtySubscription { subscribers.remove(onOutput) }
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
        // source of "process gone"). We deliberately do NOT cancel the pump here — cancelling would race
        // out that final onExit. The pump completes on its own once the destroyed process closes the PTY.
        runCatching { process?.destroy() }
    }
}
