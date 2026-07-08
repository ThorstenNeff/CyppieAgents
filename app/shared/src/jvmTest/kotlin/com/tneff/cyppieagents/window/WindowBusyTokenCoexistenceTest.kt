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
 * CYP-324 (QA tooth) — the PO's explicit COEXISTENCE axis that dev's [WindowBusyRenderTest] doesn't drive: the
 * busy `*` and the CYP-316 context-token count share the SAME title bar, so a window that is BOTH busy AND carries
 * a token value must render BOTH nodes (neither clobbers the other). Dev's render test passes only `busyFor`; this
 * drives `busyFor` + `contextTokensFor` together over the real [WindowHost].
 */
@OptIn(ExperimentalTestApi::class)
class WindowBusyTokenCoexistenceTest {

    @Test
    fun busyStar_and_tokenCount_coexist_inTheSameTitleBar() = runComposeUiTest {
        val state = WindowManagerState(listOf(WindowState("backend", "Backend", 0f, 0f, 380f, 240f)))
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) { // both axes ≥ Medium → canvas title bar (not the pager)
                    WindowHost(
                        state = state,
                        busyFor = { it == "backend" },                                  // a turn in flight → `*`
                        contextTokensFor = { if (it == "backend") 137_000 else null },  // AND a live token count
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }
        // Both render in the same title bar simultaneously — neither suppresses the other.
        onNodeWithTag(WindowTestTags.busy("backend")).assertExists()
        onNodeWithTag(WindowTestTags.busy("backend")).assertTextEquals("*")
        onNodeWithTag(WindowTestTags.contextTokens("backend")).assertExists()
    }
}
