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
 * CYP-65 — the context-token count in the PHONE-PAGER header (surface B), mirroring the canvas title-bar
 * chip (CYP-316 / [WindowContextTokensRenderTest]). Driven through the REAL [WindowHost] at a Compact width
 * so pager mode is chosen. `contextTokensFor` is the shell's fail-closed map (`null` = unknown, NOT 0).
 *
 *  - **Z1** the current page's non-null value renders the compact number under `phonePager.header.contextTokens`;
 *  - **Z2 honesty (§8-8, null ≠ 0):** a `null` value renders **no node** in the header — never a fabricated "0".
 *
 * The pager opens on `focusedId` = the LAST (top-of-z-order) window ([WindowManagerState.focusedId]), so the
 * intended "current page" is placed last in the constructor list.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp65PagerContextTokensRenderTest {

    private fun stateWithCurrent(currentId: String): WindowManagerState {
        val other = if (currentId == "backend") "frontend" else "backend"
        return WindowManagerState(
            listOf(
                WindowState(other, other.replaceFirstChar { it.uppercase() }, 0f, 0f, 200f, 150f),
                WindowState(currentId, currentId.replaceFirstChar { it.uppercase() }, 0f, 0f, 200f, 150f),
            ),
        )
    }

    @Test
    fun pagerHeader_showsCompactContext_forCurrentPage() = runComposeUiTest {
        val state = stateWithCurrent("backend")
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) { // width Compact → pager (not canvas)
                    WindowHost(
                        state = state,
                        contextTokensFor = { id -> if (id == "backend") 137_214 else null },
                        // CYP-789: a LIVE feed isolates the value-vs-null axis here — the staleness `~` marker on a
                        // non-LIVE feed is Cyp789PagerChipStalenessRenderTest's job (the default no-feed now
                        // fail-closes to stale, mirroring the canvas WindowContextTokensRenderTest).
                        connectionFor = { ConnectionStatus.LIVE },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(PhonePagerTags.PAGER).assertExists() // confirm pager mode, not canvas
        // Z1: current page = backend → header shows the compact number (137_214 → "137k").
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT).assertExists()
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT).assertTextEquals("137k")
    }

    @Test
    fun pagerHeader_absentContext_whenCurrentPageNull() = runComposeUiTest {
        val state = stateWithCurrent("frontend")
        setContent {
            MaterialTheme {
                Box(Modifier.size(360.dp, 700.dp)) { // width Compact → pager (not canvas)
                    WindowHost(
                        state = state,
                        // frontend (the current page) is null (Connector-B / pre-first-turn / unknown).
                        contextTokensFor = { id -> if (id == "backend") 137_214 else null },
                        windowContent = { Text("c ${it.id}") },
                    )
                }
            }
        }

        onNodeWithTag(PhonePagerTags.PAGER).assertExists()
        onNodeWithTag(PhonePagerTags.HEADER).assertExists() // the header IS rendered → the absence below is real
        // Z2 honesty: current page = frontend = null → NO chip node (never "0"). Mutation: render
        // `contextTokensFor(id) ?: 0` (drop the null-guard) → this would render "0" here → RED.
        onNodeWithTag(PhonePagerTags.HEADER_CONTEXT).assertDoesNotExist()
    }
}
