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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * CYP-894 (Nav-Rail S1) — the shell render teeth: the rail is present **iff** the host is eligible (landscape +
 * short-edge ≥600dp) — below/portrait renders the canvas content directly (no rail, no regression); and a
 * destination switch changes the active pane with exactly ONE active (one-active).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp894NavRailShellRenderTest {

    private val poAgent = NavDestination.Agent("po", "PO", isPo = true)
    private val dests = listOf(NavDestination.Canvas, poAgent, NavDestination.Settings)

    @Composable
    private fun Harness(w: Dp, h: Dp) {
        var selected by remember { mutableStateOf<NavDestination>(NavDestination.Canvas) }
        Box(Modifier.size(w, h)) {
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
    fun eligible_landscapeAtLeast600_railShown_canvasInPane() = runComposeUiTest {
        setContent { MaterialTheme { Harness(900.dp, 650.dp) } }
        onNodeWithTag(NavRailTags.SHELL).assertExists()
        onNodeWithTag(NavRailTags.RAIL).assertExists()
        onNodeWithTag(NavRailTags.item(NavDestination.Canvas)).assertExists()
        onNodeWithTag("pane.canvas").assertExists() // Canvas destination active by default
    }

    @Test
    fun ineligible_portrait_noRail_canvasRenderedDirectly() = runComposeUiTest {
        setContent { MaterialTheme { Harness(500.dp, 800.dp) } } // portrait → below the gate
        // No rail at all — the canvas content renders directly (current shell, unchanged).
        onNodeWithTag(NavRailTags.RAIL).assertDoesNotExist()
        onNodeWithTag(NavRailTags.SHELL).assertDoesNotExist()
        onNodeWithTag("pane.canvas").assertExists()
    }

    @Test
    fun destinationSwitch_isOneActive() = runComposeUiTest {
        setContent { MaterialTheme { Harness(900.dp, 650.dp) } }
        onNodeWithTag("pane.canvas").assertExists()
        // Switch to the PO agent destination → its pane shows, the Canvas pane is gone (exactly one active).
        onNodeWithTag(NavRailTags.item(poAgent)).performClick()
        onNodeWithTag("pane.agent.po").assertExists()
        onNodeWithTag("pane.canvas").assertDoesNotExist()
    }
}
