package com.tneff.cyppieagents.agentview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-239 — axis (b): the persona-restart badge **clears on the agent's next RUNNING lifecycle event** (the (re)spawn
 * that reads the new CLAUDE.md), and **persists across a non-RUNNING event** (a STOP does not make the new persona
 * active). This is the CLEAR half of the two-axis contract; the SET-only-on-persona half is pinned by
 * [Cyp239PersonaBadgeWiringGuardTest].
 *
 * The clear runs in [AgentViewModel]'s `lifecycleState` collector (`SharingStarted.Eagerly` on `viewModelScope`), so
 * Main is an [UnconfinedTestDispatcher] (the `AgentTurnDeliveryTest` idiom) — the eager collector then processes the
 * stub's emitted event inline.
 *
 * Mutations: (1) drop the `if (event.state == RUNNING) _personaPendingRestart.value = false` clear ⇒ the RUNNING
 * assertion REDs (stays pending after restart). (2) clear on ANY event (not just RUNNING) ⇒ the STOP assertion REDs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Cyp239PersonaRestartClearTest {

    @BeforeTest fun setUpMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDownMain() = Dispatchers.resetMain()

    private fun emptySession() = object : AgentSession {
        override val events: Flow<AgentEvent> = emptyFlow()
        override fun sendMessage(text: String) {}
    }

    @Test
    fun personaPendingRestart_clearsOnRunning_persistsAcrossStop() = runBlocking {
        val lc = StubAgentLifecycle(mapOf("backend" to AgentLifecycleState.STOPPED))
        val vm = AgentViewModel(emptySession(), agentId = "backend", lifecycle = lc, lifecycleSource = lc)
        // Touch the Eagerly lifecycleState flow so its event collector is definitely subscribed before we emit.
        vm.lifecycleState.value

        vm.markPersonaPendingRestart()
        assertTrue(vm.personaPendingRestart.value, "a CLAUDE.md persona overwrite marks pending-restart")

        // A STOP event must NOT clear it — the new persona only becomes active when the agent RUNS again.
        lc.stop("backend")
        assertTrue(vm.personaPendingRestart.value, "a STOP event leaves the reminder (persona not active until it runs)")

        // A RUNNING event = the (re)spawn read the new CLAUDE.md → the reminder is satisfied, clear it.
        lc.start("backend")
        assertFalse(vm.personaPendingRestart.value, "a RUNNING event clears the reminder (the respawn read the new file)")
    }
}
