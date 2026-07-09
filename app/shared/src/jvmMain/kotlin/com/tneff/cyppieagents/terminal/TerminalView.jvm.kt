package com.tneff.cyppieagents.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider

/**
 * CYP-334 — the Desktop terminal actual (04 §3/§4.1, CYP-331 Option D): a real JediTerm widget embedded in a
 * `SwingPanel`, driven by a [WsTtyConnector] over the PTY-over-WS [session]. JediTerm parses/renders VT/ANSI and
 * owns keyboard handling; we only wire its `TtyConnector` to the socket.
 *
 * **Z-order (04 §5, UIUX-confirmed):** `SwingPanel` renders ABOVE the Compose layer — so window chrome must be a
 * FRAME around this terminal, never a Compose overlay on top of it. That frame + the mode toggle land with the
 * CYP-333 UIUX spec; this story is only the raw terminal + WS wiring.
 */
@Composable
actual fun TerminalView(session: TerminalSession, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val widget = remember(session) {
        JediTermWidget(INITIAL_COLS, INITIAL_ROWS, DefaultSettingsProvider()).apply {
            setTtyConnector(WsTtyConnector(session, name = "cyppie-terminal", scope))
            start()
        }
    }
    // Clean teardown when the window closes / the composable leaves: stop the emulator thread and end the socket
    // (the connector's collector rides `scope`, which is cancelled with the composition).
    DisposableEffect(widget) {
        onDispose {
            runCatching { widget.close() }
            runCatching { session.close() }
        }
    }
    SwingPanel(modifier = modifier, factory = { widget })
}

private const val INITIAL_COLS = 80
private const val INITIAL_ROWS = 24
