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
import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test

/**
 * CYP-316 — the context-token render plumbing through the real [WindowHost] (mirrors the CYP-55 badge host test).
 * `contextTokensFor` is the shell's fail-closed map (a `null` = "unknown", NOT 0). These prove:
 *  - **Z1** a non-null value renders the compact number under `window.<id>.contextTokens` in the title bar;
 *  - **Z2 honesty (§8-3, null ≠ 0):** a `null` value renders **no node at all** — never a fabricated "0".
 */
@OptIn(ExperimentalTestApi::class)
class WindowContextTokensRenderTest {

    private fun twoWindows() = WindowManagerState(
        listOf(
            WindowState("backend", "Backend", 0f, 0f, 380f, 240f),
            WindowState("frontend", "Frontend", 400f, 0f, 380f, 240f),
        ),
    )

    @Test
    fun canvas_contextTokens_compactForValue_absentForNull() = runComposeUiTest {
        val state = twoWindows()
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) { // both axes ≥ Medium → canvas (not the pager)
                    WindowHost(
                        state = state,
                        // backend has a real value; frontend is null (Connector-B / pre-first-turn / unknown).
                        contextTokensFor = { id -> if (id == "backend") 137_214 else null },
                        // CYP-656: the token is greyed (not hidden) on a non-LIVE feed; a LIVE feed here isolates the
                        // value-vs-null axis (the stale/grey axis is Cyp656TokenStaleRenderTest's job).
                        connectionFor = { ConnectionStatus.LIVE },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(WindowTestTags.HOST).assertExists()
        // Z1: backend shows the compact number (137_214 → "137k") in its title bar.
        onNodeWithTag(WindowTestTags.contextTokens("backend")).assertExists()
        onNodeWithTag(WindowTestTags.contextTokens("backend")).assertTextEquals("137k")
        // Z2 honesty: frontend is null → NO node (never "0"). Mutation: render `contextTokens ?: 0` (drop the
        // null-guard) → frontend would render "0" here → RED.
        onNodeWithTag(WindowTestTags.contextTokens("frontend")).assertDoesNotExist()
    }
}
