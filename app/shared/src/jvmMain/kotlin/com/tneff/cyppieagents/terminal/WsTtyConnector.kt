package com.tneff.cyppieagents.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CYP-334 — bridges a [TerminalSession] (PTY-over-WS, CYP-332) to JediTerm's [TtyConnector] (04 §4.1). The two
 * sides mismatch in shape and this class reconciles them:
 *  - **Read side is blocking + char-based.** JediTerm's emulator thread calls [read] into a `char[]` and blocks.
 *    We collect the session's raw output bytes onto a byte [PipedOutputStream] and hand JediTerm a UTF-8
 *    [InputStreamReader] over the other end — so a multi-byte UTF-8 rune split across two WS frames still decodes
 *    correctly (the exact reason we decode at a byte pipe, not per-frame). When [TerminalSession.incoming]
 *    completes (PTY exit / socket close) the pipe closes → [read] returns -1 → JediTerm ends the session cleanly.
 *  - **Write side.** JediTerm hands us keystrokes as bytes (or a String); we forward them to [TerminalSession.send]
 *    (which Base64-encodes into `TerminalInput`). A viewport [resize] maps to [TerminalSession.resize].
 *
 * The collector runs in [scope] (the composable's scope) but is dispatched on **[Dispatchers.IO]**, NOT the
 * scope's own (Compose/EDT) dispatcher: [PipedOutputStream.write] BLOCKS once the reader falls behind and the
 * 64 KB pipe fills (a `claude` TUI redraw / `cat` / `yes` burst), and blocking the EDT would freeze the UI. IO
 * takes the blocking write; the JediTerm widget stays on the EDT. Leaving the window cancels [scope] and the
 * `finally` closes the pipe, so there is no leaked thread.
 */
class WsTtyConnector(
    private val session: TerminalSession,
    private val name: String,
    scope: CoroutineScope,
) : TtyConnector {

    private val pipeIn = PipedInputStream(PIPE_BUFFER_BYTES)
    private val pipeOut = PipedOutputStream(pipeIn)
    private val reader = InputStreamReader(pipeIn, StandardCharsets.UTF_8)

    private val connected = AtomicBoolean(true)
    private val exitLatch = CountDownLatch(1)

    private val pump: Job = scope.launch(Dispatchers.IO) {
        try {
            session.incoming.collect { bytes ->
                pipeOut.write(bytes) // blocks when the 64 KB pipe fills — on IO, never the EDT (UI-freeze fix)
                pipeOut.flush()
            }
        } finally {
            // Normal completion (PTY exit / close) OR cancellation → signal EOF to the reader so `read` unblocks
            // and returns -1, and release anyone in `waitFor`. Idempotent.
            connected.set(false)
            runCatching { pipeOut.close() }
            exitLatch.countDown()
        }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int = reader.read(buf, offset, length)

    override fun write(bytes: ByteArray) = session.send(bytes)

    override fun write(string: String) = session.send(string.toByteArray(StandardCharsets.UTF_8))

    override fun resize(termSize: TermSize) = session.resize(termSize.columns, termSize.rows)

    override fun isConnected(): Boolean = connected.get()

    override fun ready(): Boolean = reader.ready()

    /** Block until the session ends. Exit-code propagation is a follow-up (the contract's `TerminalExit.code`
     *  lives on `WsTerminalSession`, not the neutral [TerminalSession] seam); JediTerm only needs to unblock. */
    override fun waitFor(): Int {
        exitLatch.await()
        return 0
    }

    override fun getName(): String = name

    override fun close() {
        connected.set(false)
        pump.cancel()
        runCatching { pipeOut.close() }
        runCatching { reader.close() }
        session.close()
        exitLatch.countDown()
    }

    private companion object {
        const val PIPE_BUFFER_BYTES = 64 * 1024
    }
}
