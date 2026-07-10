package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-333 — the content-view mode toggle `[ Orchestrierung | Terminal ]`.
 *
 * **Honest scope (PO-ruled):** the toggle is a *client view-selection*, NOT a backend hand-off. The mediated
 * stream-json session keeps running while the terminal is shown, so no hub-blind / frozen-token / CONTEXT_LOST is
 * claimed here (that is the Backend follow-up). These teeth prove exactly the answer-independent subset:
 *  - the fail-closed operator gate (a non-operator can never open the terminal — CYP-317 no-fake-switch);
 *  - the content-rectangle swap (transcript ↔ terminal) and the composer belonging to the Orchestrierung view;
 *  - the honestly-gated state while live spawn is pending the Auftraggeber ruling.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp333ModeToggleTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    /** A test-only terminal slot — a tagged Box standing in for the Desktop `TerminalView` (which is a headless-
     *  unfriendly `SwingPanel`). Proves the swap wires the slot into the content rectangle. */
    private fun fakeTerminalTag(agentId: String) = "faketerm.$agentId"
    private val fakeTerminal: @androidx.compose.runtime.Composable (String, Modifier) -> Unit = { id, m ->
        Box(m.testTag(fakeTerminalTag(id))) { Text("TERM") }
    }

    // --- VM fail-closed gate: a non-operator can NEVER switch to TERMINAL; an operator can (MUT-A target) ---

    @Test
    fun showContentMode_failsClosed_forNonOperator_flipsForOperator() {
        val nonOp = AgentViewModel(emptySession(), agentId = "a", canControl = false)
        nonOp.showContentMode(AgentContentMode.TERMINAL)
        assertEquals(AgentContentMode.ORCHESTRATION, nonOp.contentMode.value, "non-operator cannot open the terminal")

        val op = AgentViewModel(emptySession(), agentId = "a", canControl = true)
        op.showContentMode(AgentContentMode.TERMINAL)
        assertEquals(AgentContentMode.TERMINAL, op.contentMode.value, "an operator can open the terminal")
        // Returning to Orchestrierung is always allowed (claims nothing).
        op.showContentMode(AgentContentMode.ORCHESTRATION)
        assertEquals(AgentContentMode.ORCHESTRATION, op.contentMode.value)
    }

    // --- content-rectangle swap: transcript+composer in Orchestrierung, terminal-only in Terminal (MUT-B/C) ---

    @Test
    fun operator_switchesToTerminal_swapsContent_andSuppressesComposer() = runComposeUiTest {
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(emptySession(), agentId = "backend", canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        // Orchestrierung (default): transcript + composer present, no terminal.
        onNodeWithTag(AgentViewTags.stream("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.input("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertDoesNotExist()

        // Operator flips to Terminal via the segment.
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(AgentContentMode.TERMINAL, vm.contentMode.value)

        // Terminal view: the terminal fills the content rectangle; transcript AND the mediated composer are gone
        // (the terminal has its own input — the composer is not a "mediation off" claim, just not shown here).
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.stream("backend"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.input("backend"), useUnmergedTree = true).assertDoesNotExist()

        // Toggle back → transcript + composer return.
        onNodeWithTag(AgentViewTags.modeToggleOrchestration("backend"), useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag(AgentViewTags.stream("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.input("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    // --- fail-closed render: a non-operator sees a read-only toggle (Terminal segment disabled) + the gate hint ---

    @Test
    fun nonOperator_terminalSegmentDisabled_gateHintShown() = runComposeUiTest {
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(emptySession(), agentId = "backend", canControl = false) }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag(AgentViewTags.modeToggleGateHint("backend"), useUnmergedTree = true).assertExists()
        // Even a direct VM call is fail-closed (the button being disabled is only the first line of defence).
        runOnUiThread { vm.showContentMode(AgentContentMode.TERMINAL) }
        waitForIdle()
        assertEquals(AgentContentMode.ORCHESTRATION, vm.contentMode.value)
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    // --- gated state: operator, live spawn withheld → Terminal disabled + honest "available after hand-off" note ---

    @Test
    fun operator_terminalGated_segmentDisabled_gatedNoteShown() = runComposeUiTest {
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(emptySession(), agentId = "backend", canControl = true) }
                // No terminalContent (live spawn withheld) + the gated note (the shell's TERMINAL_LIVE_SPAWN_ENABLED=false path).
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = null, terminalGatedNote = true)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag(AgentViewTags.modeToggleTerminalGated("backend"), useUnmergedTree = true).assertExists()
        // Orchestrierung segment stays reachable for the operator; the non-operator hint is NOT shown (operator).
        onNodeWithTag(AgentViewTags.modeToggleOrchestration("backend"), useUnmergedTree = true).assertIsEnabled()
        onNodeWithTag(AgentViewTags.modeToggleGateHint("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    // --- live flip: the active Shell view shows the honest "bash worktree shell" note, never the gated note ---

    @Test
    fun operator_liveShellView_showsHonestShellNote_notGatedNote() = runComposeUiTest {
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(emptySession(), agentId = "backend", canControl = true) }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        // Orchestrierung: no shell note yet (not looking at the shell), and never the gated note (backend is live).
        onNodeWithTag(AgentViewTags.modeToggleShellNote("backend"), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.modeToggleTerminalGated("backend"), useUnmergedTree = true).assertDoesNotExist()

        // Switch to the live Shell view → honest "bash worktree shell" descriptor appears, gated note stays absent.
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleShellNote("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.modeToggleTerminalGated("backend"), useUnmergedTree = true).assertDoesNotExist()
    }
}
