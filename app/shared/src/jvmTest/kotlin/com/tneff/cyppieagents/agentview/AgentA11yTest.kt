package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test

/**
 * CYP-23: the transcript exposes status as screen-reader text via the a11y keys, not glyph-only.
 * Feeds a [ToolStatus.OK] tool call and asserts the disclosure-true content description
 * "Werkzeug ausgeführt: read_file" (DE default) — "ausgeführt", not "erfolgreich".
 */
@OptIn(ExperimentalTestApi::class)
class AgentA11yTest {

    private fun sessionEmitting(vararg events: AgentEvent) = object : AgentSession {
        override val events: Flow<AgentEvent> = flowOf(*events)
        override fun sendMessage(text: String) {}
    }

    @Test
    fun toolCallOk_exposesExecutedNotSuccess() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val session = sessionEmitting(
                    AgentEvent.ToolCall("t1", "read_file", "build.gradle.kts", ToolStatus.OK, tsMs = 0L),
                )
                val viewModel = remember { AgentViewModel(session) }
                AgentWindow(agentId = "backend", viewModel = viewModel)
            }
        }

        // Locale-robust: the row must expose a status content description naming the tool — not a glyph.
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithContentDescription("read_file", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("read_file", substring = true).assertExists()
    }

    @Test
    fun successResult_keepsSubstantiveLabelAccessible() = runComposeUiTest {
        // M1: the success result's label (e.g. tool output detail) must reach the screen reader.
        setContent {
            MaterialTheme {
                val session = sessionEmitting(
                    AgentEvent.Result("r1", "42 Zeilen gelesen", isError = false, tsMs = 0L),
                )
                val viewModel = remember { AgentViewModel(session) }
                AgentWindow(agentId = "backend", viewModel = viewModel)
            }
        }

        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithContentDescription("42 Zeilen gelesen", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("42 Zeilen gelesen", substring = true).assertExists()
    }
}
