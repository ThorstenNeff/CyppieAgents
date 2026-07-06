package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-262 Teil 1 — the transient, client-only **"Startet…"** spawn feedback on the agent's StatusIndicator.
 * The lifecycle is binary STOPPED↔RUNNING with no server "starting" state, so the honest in-flight feedback is
 * a client `startPending` flag (armed on the Start action, cleared by the next lifecycle event or a synchronous
 * failure). These teeth pin the §9 honesty invariants: never "Läuft"/"Running" before the server RUNNING event
 * (§9-1); the transient always resolves and never sticks (§9-2); a spawn failure stays STOPPED honestly (§9-4);
 * fail-closed — no control ⇒ no feedback (§9-6). Renders the REAL AgentWindow/StatusIndicator via the VM.
 *
 * The real [StubAgentLifecycle.start] collapses the fire-and-forget POST and the RUNNING event into one step, so
 * it can't exercise the transient. This double models the honest split: `start()` only records the accepted
 * request (no event), and the terminal `/ws/lifecycle` event is pushed separately — exactly the gap the flag fills.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSpawnStartingTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    /** Lifecycle double where Start is fire-and-forget (no auto-event) and terminal events are pushed by hand. */
    private class PendingLifecycle(
        initial: AgentLifecycleState = AgentLifecycleState.STOPPED,
        private val startThrows: Throwable? = null,
    ) : AgentLifecycleApi, AgentLifecycleSource {
        private val _events = MutableSharedFlow<AgentLifecycleEvent>(replay = 0, extraBufferCapacity = 64)
        private val snap = mapOf("backend" to initial)
        override suspend fun snapshot(): Map<String, AgentLifecycleState> = snap
        override fun events(): Flow<AgentLifecycleEvent> = _events.asSharedFlow()
        // Fire-and-forget: the accepted POST does NOT flip state — the server confirms later on /ws/lifecycle.
        override suspend fun start(agentId: String) { startThrows?.let { throw it } }
        override suspend fun stop(agentId: String) {}
        override suspend fun restart(agentId: String) {}
        fun push(state: AgentLifecycleState, agentId: String = "backend") =
            _events.tryEmit(AgentLifecycleEvent(agentId, state))
    }

    private fun status(node: String) = AgentViewTags.status(node)

    @Test
    fun start_showsStartingTransient_neverRunningBeforeServerEvent_thenResolvesToRunning() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.STOPPED)
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()
        // Baseline: STOPPED, no pending transient.
        assertFalse(vm.startPending.value)
        onNodeWithContentDescription("Stopped", substring = true, useUnmergedTree = true).assertExists()

        // Operator clicks Start → the POST is accepted (fire-and-forget); no server event yet.
        onNodeWithTag(AgentViewTags.startBtn("backend")).performClick()
        waitForIdle()

        // §9-1 + §9-6: the honest transient "Starting…" shows — and it is NEVER "Running" before the server event.
        assertTrue(vm.startPending.value, "an accepted Start arms the transient")
        onNodeWithContentDescription("Starting", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true)
            .assertDoesNotExist() // never fake-Running before the server confirms
        // Same node/tag — 0 new tag (the transient rides the existing status indicator).
        onNodeWithTag(status("backend"), useUnmergedTree = true).assertExists()

        // §9-2: the server confirms RUNNING → the transient resolves to "Läuft"/"Running" and clears. Drive the
        // event on the UI thread and poll — the VM's lifecycle collector resolves it on the Main dispatcher.
        runOnUiThread { src.push(AgentLifecycleState.RUNNING) }
        waitUntil(timeoutMillis = 3_000L) { !vm.startPending.value }
        assertFalse(vm.startPending.value, "the RUNNING event resolves the transient")
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Starting", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun startingTransient_resolvesToStopped_onStoppedEvent_neverSticks() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.STOPPED)
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()

        onNodeWithTag(AgentViewTags.startBtn("backend")).performClick()
        waitForIdle()
        assertTrue(vm.startPending.value)
        onNodeWithContentDescription("Starting", substring = true, useUnmergedTree = true).assertExists()

        // §9-2: a terminal STOPPED event (start did not take) also resolves the transient — it never sticks.
        runOnUiThread { src.push(AgentLifecycleState.STOPPED) }
        waitUntil(timeoutMillis = 3_000L) { !vm.startPending.value }
        assertFalse(vm.startPending.value, "any terminal lifecycle event clears the transient")
        onNodeWithContentDescription("Stopped", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Starting", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun spawnFailure_resolvesTransient_staysStopped_showsHonestReason() = runComposeUiTest {
        val src = PendingLifecycle(
            AgentLifecycleState.STOPPED,
            startThrows = AgentLifecycleHttpException(status = 503, code = "spawn_failed"),
        )
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = true)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()

        onNodeWithTag(AgentViewTags.startBtn("backend")).performClick()
        waitForIdle()

        // §9-4: a synchronous spawn failure clears the transient at once, stays STOPPED (never fake-Running),
        // and surfaces the server's honest reason ("Start failed") in the reused LifecycleErrorRow.
        assertFalse(vm.startPending.value, "a failed start resolves the transient — no stuck spinner")
        onNodeWithContentDescription("Stopped", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Starting", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.lifecycleError("backend"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun failClosed_nonOperator_startIsNoOp_noStartingTransient() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.STOPPED)
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                val v = remember {
                    AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = false)
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()

        // §9-6 fail-closed: without operator control, start() is a no-op → the transient is NEVER armed (a
        // non-operator must not even see "Starting…"). The Start button is disabled, so drive the VM directly.
        runOnUiThread { vm.start() }
        waitForIdle()
        assertFalse(vm.startPending.value, "no control ⇒ no spawn feedback")
        onNodeWithContentDescription("Starting", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Stopped", substring = true, useUnmergedTree = true).assertExists()
    }
}
