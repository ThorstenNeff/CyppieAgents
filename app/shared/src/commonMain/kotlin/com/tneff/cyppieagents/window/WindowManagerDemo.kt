package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Self-contained demo of the window manager with placeholder content, used to exercise
 * drag/resize/focus/overlap without depending on the renderer (CYP-6).
 *
 * Intentionally **not** wired into [com.tneff.cyppieagents.App] from this module: `App.kt` is shared
 * with CYP-6 and the coordinator owns hanging the window manager into the app shell (and marrying it
 * with the real renderer as window content) at merge time.
 */
@Composable
fun WindowManagerDemo(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val hostWidth = maxWidth.value
        val hostHeight = maxHeight.value
        val state = remember {
            WindowManagerState(
                WindowReducer.tile(
                    items = listOf(
                        "po" to "Product Owner",
                        "frontend" to "Frontend",
                        "backend" to "Backend",
                    ),
                    hostWidth = hostWidth,
                    hostHeight = hostHeight,
                ),
            )
        }
        WindowHost(
            state = state,
            windowContent = { window -> StubWindowContent(window) },
        )
    }
}

/** Placeholder body standing in for CYP-6's renderer until it lands. */
@Composable
private fun StubWindowContent(window: WindowState) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = "Stub content · ${window.id}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Agent renderer (CYP-6) plugs in here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
