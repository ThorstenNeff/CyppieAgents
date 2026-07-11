package com.tneff.cyppieagents.agentview

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.tneff.cyppieagents.model.AgentBusyStateEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-381 (Dev) — the toggle wired to the **non-optimistic hand-off command** through [ModeRepository]
 * (`POST /api/agents/{id}/mode`). Built against the settled CYP-355 shape; the StubModeRepository stands in until
 * the motor endpoint is real. These teeth prove the answer-independent invariants:
 *  - **non-optimistic:** the view flips ONLY after the server confirm — never on the click (CompactVM discipline);
 *  - **reject:** a REJECTED outcome leaves the mode unchanged and surfaces the honest reason (stayed put);
 *  - **IDLE-gate:** a take-over issued mid-turn shows the "wartet bis Turn fertig" waiting hint (server holds the
 *    bounded-wait), distinct from a plain switch — never a silent hijack;
 *  - **fail-closed:** a non-operator take-over never even reaches the repository.
 *  jvmTest render locale = EN. Renders the REAL AgentWindow so the toggle→VM→view path is exercised end-to-end.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp381ModeRequestTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private fun fakeTerminalTag(agentId: String) = "faketerm.$agentId"
    private val fakeTerminal: @androidx.compose.runtime.Composable (String, Modifier) -> Unit = { id, m ->
        Box(m.testTag(fakeTerminalTag(id))) { Text("TERM") }
    }

    /** A controllable [ModeRepository]: optionally [gate]d (setMode suspends until released) to observe the in-flight
     *  window, and optionally [rejectCode] (throws [ModeChangeException]) to exercise the reject path. */
    private class GateRepo(
        private val gate: CompletableDeferred<Unit>? = null,
        private val rejectCode: String? = null,
    ) : ModeRepository {
        var calls = 0
        override suspend fun setMode(agentId: String, target: AgentContentMode): ModeConfirm {
            calls++
            gate?.await()
            rejectCode?.let { throw ModeChangeException(it) }
            return ModeConfirm(confirmed = target)
        }
    }

    private class FakeBusySource(private val flow: Flow<AgentBusyStateEvent>) : BusyStateSource {
        override fun events(): Flow<AgentBusyStateEvent> = flow
    }

    // --- non-optimistic: the view flips ONLY after the confirm, never on the click ---

    @Test
    fun requestTerminal_doesNotFlip_untilServerConfirms() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val repo = GateRepo(gate = gate)
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", canControl = true, modeRepository = repo)
                }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).performClick()
        waitForIdle()
        // Command issued + the waiting UI is up, but the view has NOT flipped — still the transcript, not the terminal.
        // Mutation: adopt the target optimistically (flip before the confirm) → the terminal would already show → RED.
        assertTrue(vm.modeSwitching.value, "the in-flight command shows the waiting state")
        assertEquals(AgentContentMode.ORCHESTRATION, vm.contentMode.value, "no optimistic flip before confirm")
        onNodeWithTag(AgentViewTags.modeSwitching("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertDoesNotExist()

        // Server confirms → NOW the view flips to the terminal and the waiting state clears.
        runOnUiThread { gate.complete(Unit) }
        waitUntil(timeoutMillis = 3_000L) { vm.contentMode.value == AgentContentMode.TERMINAL }
        assertEquals(1, repo.calls)
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertExists()
        assertTrue(!vm.modeSwitching.value, "the confirm clears the waiting state")
    }

    // --- reject: a REJECTED outcome leaves the mode unchanged and surfaces the honest reason ---

    @Test
    fun requestTerminal_reject_staysInOrchestration_surfacesError() = runComposeUiTest {
        val repo = GateRepo(rejectCode = "BUSY_TIMEOUT")
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", canControl = true, modeRepository = repo)
                }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).performClick()
        waitUntil(timeoutMillis = 3_000L) { vm.modeError.value != null }
        // Mutation: adopt the target on failure (drop the onFailure guard) → contentMode would be TERMINAL → RED.
        assertEquals(AgentContentMode.ORCHESTRATION, vm.contentMode.value, "a reject never flips the view")
        assertEquals("BUSY_TIMEOUT", vm.modeError.value)
        onNodeWithTag(AgentViewTags.modeError("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(fakeTerminalTag("backend"), useUnmergedTree = true).assertDoesNotExist()
    }

    // --- IDLE-gate: a take-over mid-turn shows the distinct "wartet bis Turn fertig" waiting hint ---

    @Test
    fun requestTerminal_whileBusy_showsWaitingHint_notPlainSwitch() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val repo = GateRepo(gate = gate)
        val busy = MutableSharedFlow<AgentBusyStateEvent>(replay = 1, extraBufferCapacity = 8)
        busy.tryEmit(AgentBusyStateEvent("backend", busy = true)) // a turn is in flight
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember {
                    AgentViewModel(
                        emptySession(), agentId = "backend", canControl = true,
                        modeRepository = repo, busySource = FakeBusySource(busy.asSharedFlow()),
                    )
                }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitUntil(timeoutMillis = 3_000L) { vm.busy.value } // the busy feed reached the VM
        onNodeWithTag(AgentViewTags.modeToggleTerminal("backend"), useUnmergedTree = true).performClick()
        waitForIdle()
        // Busy + in-flight → the honest "waiting for the turn" hint, NOT the plain "switching…" (no silent hijack).
        // Mutation: drop `busy` from the branch (`switching && busy` → `switching`) → the plain hint shows → RED.
        onNodeWithTag(AgentViewTags.modeDeferred("backend"), useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.modeSwitching("backend"), useUnmergedTree = true).assertDoesNotExist()
        runOnUiThread { gate.complete(Unit) } // let the (server-held) command settle so the scope ends cleanly
        waitForIdle()
    }

    // --- fail-closed: a non-operator take-over never reaches the repository ---

    @Test
    fun failClosed_nonOperator_requestTerminal_neverCallsRepo() = runComposeUiTest {
        val repo = GateRepo()
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember {
                    AgentViewModel(emptySession(), agentId = "backend", canControl = false, modeRepository = repo)
                }
                AgentWindow(agentId = "backend", viewModel = vm, terminalContent = fakeTerminal)
            }
        }
        waitForIdle()
        runOnUiThread { vm.requestMode(AgentContentMode.TERMINAL) } // segment is disabled → drive the VM directly
        waitForIdle()
        // Mutation: drop the `!canControl` guard → the repo would be called + the view could flip → RED.
        assertEquals(0, repo.calls, "a non-operator take-over never reaches the endpoint")
        assertEquals(AgentContentMode.ORCHESTRATION, vm.contentMode.value)
    }
}
