package com.tneff.cyppieagents.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test

/**
 * CYP-897 (Nav-Rail S4) — settings destination + polish teeth:
 *  • the settings destination routes through the real [SettingsDestinationPane] `renderSettings` seam (not a placeholder);
 *  • one-active-destination holds across ALL FOUR destination kinds (Canvas / two agents / Settings);
 *  • an empty worker roster yields a clean rail (Canvas + Settings only, no dead agent rows, no crash/blank).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp897SettingsPolishRenderTest {

    private fun agent(id: String, role: Role) = Agent(id, id.uppercase(), role, id)

    @Test
    fun settingsDestinationPane_routesThroughRenderSettingsSeam() = runComposeUiTest {
        var rendered = false
        setContent {
            MaterialTheme {
                SettingsDestinationPane(renderSettings = {
                    rendered = true
                    Box(Modifier.testTag("settingsSlot"))
                })
            }
        }
        onNodeWithTag(NavRailTags.PANE_SETTINGS).assertExists()
        // MUT (placeholder / ignores the seam): the real settings surface never renders → slot absent → reds.
        onNodeWithTag("settingsSlot").assertExists()
        kotlin.test.assertTrue(rendered, "settings destination must render the injected real settings surface")
    }

    @Composable
    private fun FourDestHarness() {
        val po = NavDestination.Agent("po", "PO", Role.PO)
        val w1 = NavDestination.Agent("w1", "W1", Role.WORKER)
        val dests = listOf(NavDestination.Canvas, po, w1, NavDestination.Settings)
        var selected by remember { mutableStateOf<NavDestination>(NavDestination.Canvas) }
        Box(Modifier.size(1000.dp, 700.dp)) {
            NavRailShell(
                destinations = dests,
                selected = selected,
                onSelect = { selected = it },
                labelFor = { d ->
                    when (d) {
                        NavDestination.Canvas -> "Canvas"
                        is NavDestination.Agent -> d.label
                        NavDestination.Settings -> "Settings"
                    }
                },
                paneContent = { d ->
                    when (d) {
                        NavDestination.Canvas -> Box(Modifier.fillMaxSize().testTag("pane.canvas"))
                        is NavDestination.Agent -> Box(Modifier.fillMaxSize().testTag("pane.agent.${d.agentId}"))
                        NavDestination.Settings -> Box(Modifier.fillMaxSize().testTag("pane.settings"))
                    }
                },
            )
        }
    }

    @Test
    fun oneActive_holdsAcrossAllFourDestinations() = runComposeUiTest {
        setContent { MaterialTheme { FourDestHarness() } }
        val po = NavDestination.Agent("po", "PO", Role.PO)
        val w1 = NavDestination.Agent("w1", "W1", Role.WORKER)

        // Canvas active by default; the other three panes are absent.
        onNodeWithTag("pane.canvas").assertExists()
        onNodeWithTag("pane.agent.po").assertDoesNotExist()
        onNodeWithTag("pane.settings").assertDoesNotExist()

        // Each switch makes exactly its pane the only one present.
        onNodeWithTag(NavRailTags.item(po)).performClick()
        onNodeWithTag("pane.agent.po").assertExists()
        onNodeWithTag("pane.canvas").assertDoesNotExist()
        onNodeWithTag("pane.agent.w1").assertDoesNotExist()

        onNodeWithTag(NavRailTags.item(w1)).performClick()
        onNodeWithTag("pane.agent.w1").assertExists()
        onNodeWithTag("pane.agent.po").assertDoesNotExist()

        onNodeWithTag(NavRailTags.item(NavDestination.Settings)).performClick()
        onNodeWithTag("pane.settings").assertExists()
        onNodeWithTag("pane.agent.w1").assertDoesNotExist()

        onNodeWithTag(NavRailTags.item(NavDestination.Canvas)).performClick()
        onNodeWithTag("pane.canvas").assertExists()
        onNodeWithTag("pane.settings").assertDoesNotExist()
    }

    @Test
    fun emptyRoster_railHasCanvasAndSettingsOnly_canvasEmptyStateRenders() = runComposeUiTest {
        // Destinations built from an EMPTY roster, exactly as AgentShell builds them.
        val dests = buildList {
            add(NavDestination.Canvas)
            addAll(navAgentDestinations(emptyList())) // empty worker list → no agent destinations
            add(NavDestination.Settings)
        }
        setContent {
            MaterialTheme {
                Box(Modifier.size(1000.dp, 700.dp)) {
                    NavRailShell(
                        destinations = dests,
                        selected = NavDestination.Canvas,
                        onSelect = {},
                        labelFor = { d -> if (d is NavDestination.Agent) d.label else d.toString() },
                        paneContent = { d ->
                            // The Canvas pane hosts the REAL agent-less empty-state; no crash/blank with 0 agents.
                            if (d is NavDestination.Canvas) {
                                WindowHost(
                                    state = WindowManagerState(emptyList()),
                                    agentsEmpty = true,
                                    canAddAgent = true,
                                    onAddFirstAgent = {},
                                    windowContent = {},
                                )
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        },
                    )
                }
            }
        }
        // Rail offers only Canvas + Settings — no dead agent rows.
        onNodeWithTag(NavRailTags.item(NavDestination.Canvas)).assertExists()
        onNodeWithTag(NavRailTags.item(NavDestination.Settings)).assertExists()
        onNodeWithTag(NavRailTags.item(NavDestination.Agent("po", "PO", Role.PO))).assertDoesNotExist()
        // …and the canvas empty-state renders cleanly under the rail.
        onNodeWithTag(WindowTestTags.EMPTY).assertExists()
    }
}
