package com.tneff.cyppieagents.window

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-250 — the desktop (canvas) empty-state for a 0-AGENT project. Drives the real [WindowHost]/[WindowCanvas]
 * through Compose's UI-test infra. The empty-state triggers on `agentsEmpty` (the agent list), NOT on the window
 * count — the tool windows always coexist — so these render with tool windows present AND agentsEmpty=true to pin
 * that decoupling (§9-1/§9-2). The default test surface is Medium+ on both axes, so [WindowHost] renders the
 * canvas (not the phone pager) — the same assumption AgentShellRenderTest relies on.
 */
@OptIn(ExperimentalTestApi::class)
class WindowCanvasEmptyStateTest {

    /** Two always-present tool windows (agent-management + comm) — the desktop is agent-less, not window-less. */
    private fun toolWindowsState() = WindowManagerState(
        listOf(
            WindowState("agentMgmt", "Agenten", 40f, 60f, 280f, 180f),
            WindowState("comm", "Kommunikation", 340f, 60f, 280f, 180f),
        ),
    )

    @Test
    fun emptyState_shownWhenAgentsEmpty_coexistingWithToolWindows() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WindowHost(
                    state = toolWindowsState(),
                    agentsEmpty = true,
                    canAddAgent = true,
                    windowContent = { Text("stub ${it.id}") },
                )
            }
        }
        // §9-1/§9-2: the empty-state is present WHILE the tool windows are also present (decoupled from window count).
        onNodeWithTag(WindowTestTags.EMPTY).assertExists()
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()
        onNodeWithTag(WindowTestTags.window("agentMgmt")).assertExists()
        // Operator → CTA present + enabled, no gate hint.
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).assertExists().assertIsEnabled()
        onNodeWithTag(WindowTestTags.EMPTY_GATE_HINT).assertDoesNotExist()
    }

    @Test
    fun emptyState_selfClears_whenAgentsPresent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WindowHost(
                    state = toolWindowsState(),
                    agentsEmpty = false, // ≥1 agent → the empty-state is gone
                    canAddAgent = true,
                    windowContent = { Text("stub ${it.id}") },
                )
            }
        }
        onNodeWithTag(WindowTestTags.EMPTY).assertDoesNotExist()
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).assertDoesNotExist()
        // The tool windows are unaffected.
        onNodeWithTag(WindowTestTags.window("comm")).assertExists()
    }

    @Test
    fun emptyState_operatorCta_routesIntoTheAddFlow() = runComposeUiTest {
        var addFlow = 0
        setContent {
            MaterialTheme {
                WindowHost(
                    state = toolWindowsState(),
                    agentsEmpty = true,
                    canAddAgent = true,
                    onAddFirstAgent = { addFlow++ },
                    windowContent = { Text("stub ${it.id}") },
                )
            }
        }
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).performClick()
        assertEquals(1, addFlow, "the CTA routes into the existing openAdd flow exactly once")
    }

    @Test
    fun emptyState_nonOperator_ctaDisabled_withHonestGateHint() = runComposeUiTest {
        setContent {
            MaterialTheme {
                WindowHost(
                    state = toolWindowsState(),
                    agentsEmpty = true,
                    canAddAgent = false, // non-operator
                    windowContent = { Text("stub ${it.id}") },
                )
            }
        }
        // §9-5: no dead CTA — disabled button + the honest "why" gate hint.
        onNodeWithTag(WindowTestTags.EMPTY_ADD_BTN).assertExists().assertIsNotEnabled()
        onNodeWithTag(WindowTestTags.EMPTY_GATE_HINT).assertExists()
    }
}
