package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-324 — the busy `*` render plumbing through the real [WindowHost] (mirrors the CYP-316 context-token host test).
 * `busyFor` is the shell's fail-closed map (an absent key / `false` = "not busy", NEVER a fabricated `*`). These prove:
 *  - **Z1** a `true` value renders the `*` under `window.<id>.busy` in the title bar;
 *  - **Z2 honesty (unknown ≠ busy):** a `false` value renders **no node at all** — never a stale/idle `*`.
 */
@OptIn(ExperimentalTestApi::class)
class WindowBusyRenderTest {

    private fun twoWindows() = WindowManagerState(
        listOf(
            WindowState("backend", "Backend", 0f, 0f, 380f, 240f),
            WindowState("frontend", "Frontend", 400f, 0f, 380f, 240f),
        ),
    )

    @Test
    fun canvas_busyStar_presentForBusy_absentForIdle() = runComposeUiTest {
        val state = twoWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) { // both axes ≥ Medium → canvas (not the pager)
                    WindowHost(
                        state = state,
                        // backend has a turn in flight; frontend is idle (or pre-first-turn / unknown).
                        busyFor = { id -> id == "backend" },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // Z1: backend shows the `*` in its title bar.
        onNodeWithTag(WindowTestTags.busy("backend")).assertExists()
        onNodeWithTag(WindowTestTags.busy("backend")).assertTextEquals("*")
        // Z2 honesty: frontend is not busy → NO node (never a stale `*`). Mutation: render the `*` unconditionally
        // (drop the `if (busy)` guard) → frontend would render a `*` here → RED.
        onNodeWithTag(WindowTestTags.busy("frontend")).assertDoesNotExist()
    }
}
