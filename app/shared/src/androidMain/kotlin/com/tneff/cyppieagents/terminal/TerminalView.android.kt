package com.tneff.cyppieagents.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** CYP-334 — compile-only stub (Option D is Desktop-focused; no interactive terminal on Android). */
@Composable
actual fun TerminalView(session: TerminalSession, modifier: Modifier) {
    Box(modifier.padding(8.dp)) { Text("Interactive terminal is available on Desktop only.") }
}
