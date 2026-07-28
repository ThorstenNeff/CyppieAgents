package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-896 (S3, ★★ load-bearing) — the maximized agent destination renders through the SAME injected [renderAgent]
 * seam the canvas uses (in prod: `agentVms[id]?.let { AgentWindow(viewModel = it) }`, the hoisted VM). So it is the
 * SAME `AgentViewModel` instance maximized — no 2nd VM, no 2nd render path. This pins that routing: the pane calls
 * `renderAgent(id)` exactly once with the requested id, inside its `agentPane(id)` tag.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp896AgentMaximizeRenderTest {

    @Test
    fun maximizedAgentPane_routesThroughTheSharedRenderAgentSeam() = runComposeUiTest {
        val rendered = mutableListOf<String>()
        setContent {
            MaterialTheme {
                AgentDestinationPane(
                    agentId = "po",
                    // Spy for the prod `agentVms[id]?.let { AgentWindow(viewModel = it) }` seam.
                    renderAgent = { id ->
                        rendered.add(id)
                        Box(Modifier.testTag("agentSlot.$id"))
                    },
                )
            }
        }
        onNodeWithTag(NavRailTags.agentPane("po")).assertExists()
        // MUT (2nd render path / not the seam): the pane rendered something OTHER than renderAgent("po") → this
        // slot is absent + `rendered` empty → reds.
        onNodeWithTag("agentSlot.po").assertExists()
        assertEquals(listOf("po"), rendered) // exactly one render, via the shared seam, for the requested id
    }
}
