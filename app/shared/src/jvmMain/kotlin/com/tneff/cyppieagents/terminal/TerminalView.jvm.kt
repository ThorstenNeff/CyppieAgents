package com.tneff.cyppieagents.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * CYP-334 — Desktop terminal actual. Placeholder until the JediTerm binding lands (next commit): the real
 * implementation is a `JediTermWidget` in a `SwingPanel` driven by [WsTtyConnector] over the [session].
 */
@Composable
actual fun TerminalView(session: TerminalSession, modifier: Modifier) {
    Box(modifier.padding(8.dp)) { Text("Terminal (Desktop) — JediTerm binding pending.") }
}
