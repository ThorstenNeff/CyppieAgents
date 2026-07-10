package com.tneff.cyppieagents.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow

/**
 * CYP-334 / 04 §3 — the ONE platform-specific UI seam (re-activates the terminal `expect`/`actual` that 05-D6
 * had removed; CYP-331 Option D). Everything else (window manager, comm, ACL, state) is `commonMain`; only the
 * terminal *renderer* differs per target:
 *  - **Desktop (jvm):** a real JediTerm widget in a `SwingPanel`, bound to the PTY-over-WS via [WsTtyConnector].
 *  - **Other targets (wasmJs/js/android/ios):** a compile-only stub — Option D is Desktop-focused (no Web/Wasm
 *    interactive terminal for this team).
 */
@Composable
expect fun TerminalView(session: TerminalSession, modifier: Modifier = Modifier)

/**
 * 04 §3 — the platform-neutral terminal session: raw PTY bytes both ways over `/ws/terminal?agentId=…`
 * (CYP-332 contract). Only the byte transport is shared here; rendering + keyboard handling are per-target.
 *
 * [incoming] is the decoded PTY **stdout** byte stream and **completes** when the PTY exits (`TerminalExit`) or
 * the socket closes — the renderer tears down on completion. [send]/[resize] are invoked from the renderer's
 * input thread (synchronous, fire-and-forget); [close] ends the session and its socket.
 */
interface TerminalSession {
    val incoming: Flow<ByteArray>
    fun send(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}
