package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-330 Teil 1 — the transient, client-only **"Neustart…"** restart feedback on the agent's StatusIndicator.
 *
 * The bug: a restart is typically **RUNNING→RUNNING**, and the client discards the 200 (the `/ws/lifecycle` delta
 * drives the header) — so a *successful* restart produced no visible delta and the first click looked "swallowed".
 * The fix arms a `restartPending` transient synchronously on the accepted Restart action (instant, perceivable
 * feedback), resolved by the next lifecycle event / a synchronous failure / a watchdog — the exact honesty shape
 * of CYP-262's `startPending`. Also: Restart is no longer hard-disabled in UNKNOWN/STOPPED.
 *
 * Renders the REAL AgentWindow/StatusIndicator via the VM; the [PendingLifecycle] double models the honest split
 * (restart records the accepted request but emits NO event; the terminal `/ws/lifecycle` event is pushed by hand).
 */
@OptIn(ExperimentalTestApi::class)
class Cyp330RestartFeedbackTest {

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    private class PendingLifecycle(
        initial: AgentLifecycleState? = AgentLifecycleState.RUNNING,
        private val restartThrows: Throwable? = null,
    ) : AgentLifecycleApi, AgentLifecycleSource {
        private val _events = MutableSharedFlow<AgentLifecycleEvent>(replay = 0, extraBufferCapacity = 64)
        // initial == null → the snapshot omits the agent → the header stays UNKNOWN (never guesses a server fact).
        private val snap = if (initial == null) emptyMap() else mapOf("backend" to initial)
        var restartCalls = 0
        override suspend fun snapshot(): Map<String, AgentLifecycleState> = snap
        override fun events(): Flow<AgentLifecycleEvent> = _events.asSharedFlow()
        override suspend fun start(agentId: String) {}
        override suspend fun stop(agentId: String) {}
        // Fire-and-forget: the accepted POST does NOT flip state — the server confirms later on /ws/lifecycle.
        override suspend fun restart(agentId: String) { restartCalls++; restartThrows?.let { throw it } }
        fun push(state: AgentLifecycleState, agentId: String = "backend") =
            _events.tryEmit(AgentLifecycleEvent(agentId, state))
    }

    // --- the core fix: a RUNNING→RUNNING restart is visibly acknowledged, WITHOUT the first click being swallowed ---

    @Test
    fun restart_showsRestartingTransient_immediately_thenResolvesOnRunningEvent() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.RUNNING)
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
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true).assertExists()
        assertFalse(vm.restartPending.value)

        // Operator clicks Restart → the POST is accepted (fire-and-forget), NO server event yet.
        onNodeWithTag(AgentViewTags.restartBtn("backend")).performClick()
        waitForIdle()

        // The first click is IMMEDIATELY perceivable: "Neustart…" shows even though the state is still RUNNING
        // (RUNNING→RUNNING) — the fix for the "swallowed" click. It rides the existing status node (0 new tag).
        assertTrue(vm.restartPending.value, "an accepted Restart arms the transient synchronously")
        assertEquals(1, src.restartCalls)
        onNodeWithContentDescription("Restarting", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithTag(AgentViewTags.status("backend"), useUnmergedTree = true).assertExists()

        // The server re-confirms RUNNING → the transient resolves and the label returns to "Running". Even a
        // same-state RUNNING event clears the flash → a successful restart is acknowledged, never stuck.
        runOnUiThread { src.push(AgentLifecycleState.RUNNING) }
        waitUntil(timeoutMillis = 3_000L) { !vm.restartPending.value }
        assertFalse(vm.restartPending.value, "the lifecycle event resolves the restart transient")
        onNodeWithContentDescription("Restarting", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithContentDescription("Running", substring = true, useUnmergedTree = true).assertExists()
    }

    // --- never sticks: a server-silent restart resolves via the watchdog ---

    @Test
    fun restartTransient_watchdog_resolves_whenServerNeverEmits() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.RUNNING) // restart() records the request but emits NO event
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                val v = remember {
                    AgentViewModel(
                        emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src,
                        canControl = true, startPendingTimeoutMs = 300L, // tiny window so the fallback is observable
                    )
                }
                vm = v
                AgentWindow(agentId = "backend", viewModel = v)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.restartBtn("backend")).performClick()
        waitForIdle()
        assertTrue(vm.restartPending.value, "an accepted Restart arms the transient")
        onNodeWithContentDescription("Restarting", substring = true, useUnmergedTree = true).assertExists()

        // No lifecycle event ever arrives → the watchdog resolves it (never a stuck "Neustart…", §9-2).
        waitUntil(timeoutMillis = 5_000L) { !vm.restartPending.value }
        assertFalse(vm.restartPending.value, "the watchdog resolves a never-confirmed restart — no stuck spinner")
        onNodeWithContentDescription("Restarting", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    // --- a synchronous failure resolves the transient and surfaces the honest reason ---

    @Test
    fun restartFailure_resolvesTransient_showsHonestReason() = runComposeUiTest {
        val src = PendingLifecycle(
            AgentLifecycleState.RUNNING,
            restartThrows = AgentLifecycleHttpException(status = 503, code = "spawn_failed"),
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
        onNodeWithTag(AgentViewTags.restartBtn("backend")).performClick()
        waitForIdle()

        // A synchronous restart failure clears the transient at once and surfaces the server's honest reason.
        assertFalse(vm.restartPending.value, "a failed restart resolves the transient — no stuck spinner")
        onNodeWithContentDescription("Restarting", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(AgentViewTags.lifecycleError("backend"), useUnmergedTree = true).assertExists()
    }

    // --- fail-closed: a non-operator restart is a no-op and shows no transient ---

    @Test
    fun failClosed_nonOperator_restartIsNoOp_noTransient() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.RUNNING)
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
        runOnUiThread { vm.restart() } // button is disabled, so drive the VM directly
        waitForIdle()
        assertFalse(vm.restartPending.value, "no control ⇒ no restart feedback")
        assertEquals(0, src.restartCalls, "a non-operator restart never reaches the api")
    }

    // --- Nebengate: Restart is NOT hard-disabled in STOPPED/UNKNOWN for an operator ---

    @Test
    fun restartButton_enabledForOperator_inStopped_andUnknown() = runComposeUiTest {
        setContent {
            MaterialTheme {
                val stoppedSrc = remember { PendingLifecycle(AgentLifecycleState.STOPPED) }
                val unknownSrc = remember { PendingLifecycle(initial = null) } // snapshot omits agent → UNKNOWN
                val stoppedVm = remember { AgentViewModel(emptySession(), agentId = "s", lifecycle = stoppedSrc, lifecycleSource = stoppedSrc, canControl = true) }
                val unknownVm = remember { AgentViewModel(emptySession(), agentId = "u", lifecycle = unknownSrc, lifecycleSource = unknownSrc, canControl = true) }
                androidx.compose.foundation.layout.Column {
                    AgentWindow(agentId = "s", viewModel = stoppedVm)
                    AgentWindow(agentId = "u", viewModel = unknownVm)
                }
            }
        }
        waitForIdle()
        // Was `state == RUNNING || state == ERROR` → dead in STOPPED/UNKNOWN. Now enabled for any operator.
        onNodeWithTag(AgentViewTags.restartBtn("s")).assertIsEnabled()
        onNodeWithTag(AgentViewTags.restartBtn("u")).assertIsEnabled()
    }

    @Test
    fun restartButton_disabledForNonOperator_regression() = runComposeUiTest {
        val src = PendingLifecycle(AgentLifecycleState.STOPPED)
        setContent {
            MaterialTheme {
                val vm = remember { AgentViewModel(emptySession(), agentId = "backend", lifecycle = src, lifecycleSource = src, canControl = false) }
                AgentWindow(agentId = "backend", viewModel = vm)
            }
        }
        waitForIdle()
        onNodeWithTag(AgentViewTags.restartBtn("backend")).assertIsNotEnabled() // fail-closed unchanged
    }
}
