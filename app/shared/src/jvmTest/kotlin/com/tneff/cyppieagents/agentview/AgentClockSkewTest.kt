package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-335 A2 — **two clocks feed one ascending column.** Stream rows are stamped by the server; the composer's
 * [AgentEvent.UserTurn] and the `conn-error` [AgentEvent.Notice] can only read the client's clock. A browser
 * running five minutes fast renders your own message at `14:08` and the agent's reply beneath it at `14:03`:
 * the agent answered before you asked.
 *
 * These tests assert the **invariant** ("the column never falls"), not a literal `14:03`. A literal expectation
 * waves through any fix as soon as someone writes the matching number; the invariant does not. Three mechanisms
 * cooperate, and each is killed by its own case below — a mechanism no test can kill is a mechanism the next
 * person deletes:
 *
 *  1. **skew** — client rows are stamped on the server's time base. Killed by [fastBrowser_columnNeverFalls].
 *  2. **re-base at the first anchor** — bootstrap: the first turn precedes every server event, so the event
 *     that establishes the skew lands *after* it. Killed by [bootstrapTurn_columnNeverFalls].
 *  3. **clamp against the previous row** — the client clock is not monotonic (an NTP correction moves it
 *     backwards). Killed by [clientClockJumpsBackwards_columnNeverFalls].
 */
@OptIn(ExperimentalTestApi::class)
class AgentClockSkewTest {

    private val agentId = "backend"
    private val minute = 60_000L

    /** 2026-07-09T20:00:00Z, the server's base. */
    private val serverBase = 1_783_627_200_000L

    /** A hand-driven session + a hand-driven client clock. */
    private class Rig(var clientNowMs: Long) {
        val bus = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
        val session = object : AgentSession {
            override val events: Flow<AgentEvent> = bus
            override fun sendMessage(text: String) {}
        }
    }

    private fun assertNeverFalls(rows: List<AgentEvent>) {
        val stamps = rows.map { it.tsMs }
        assertTrue(
            stamps.zipWithNext().all { (a, b) -> a <= b },
            "the time column must never fall; got ${stamps.map { formatLocalHhMm(it) }} ($stamps)",
        )
    }

    /** Runs [body] against a live [AgentViewModel] whose clock is [Rig.clientNowMs]. */
    private fun withVm(rig: Rig, body: ComposeRunner.(AgentViewModel) -> Unit) = runComposeUiTest {
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(rig.session, agentId, nowMs = { rig.clientNowMs }) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        ComposeRunner(this).body(vm)
    }

    /** Thin wrapper so the helpers below can drive the composition without repeating `waitUntil` bodies. */
    private class ComposeRunner(private val test: androidx.compose.ui.test.ComposeUiTest) {
        fun settle() = test.waitForIdle()
        fun awaitRows(vm: AgentViewModel, count: Int) {
            test.waitUntil(timeoutMillis = 5_000L) { vm.transcript.value.size >= count }
        }
    }

    // --- 1. skew: the browser runs FAST. A clamp alone cannot catch this. ---

    @Test
    fun fastBrowser_columnNeverFalls() {
        // The browser is 5 minutes ahead of the server.
        val rig = Rig(clientNowMs = serverBase + 5 * minute)
        withVm(rig) { vm ->
            // A server event first (anchors the skew at −5 min), then the human turn, then the agent's reply.
            rig.bus.tryEmit(AgentEvent.Notice("s-1", "Session gestartet", tsMs = serverBase))
            awaitRows(vm, 1)

            vm.onSend("Wie ist der Stand?")
            settle()

            // The reply is stamped by the server one minute after the session started.
            rig.clientNowMs = serverBase + 6 * minute
            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "Gleich.", complete = true, tsMs = serverBase + minute))
            awaitRows(vm, 3)

            assertEquals(3, vm.transcript.value.size)
            assertNeverFalls(vm.transcript.value)
            // And concretely: the turn sits between the two server rows, not five minutes above them.
            val turn = vm.transcript.value[1]
            assertTrue(turn is AgentEvent.UserTurn)
            assertTrue(
                turn.tsMs in serverBase..(serverBase + minute),
                "the turn must be re-expressed on the server's base, got ${turn.tsMs} (base $serverBase)",
            )
        }
    }

    // --- 2. re-base: bootstrap. The turn precedes every server event. A skew alone cannot catch this. ---

    @Test
    fun bootstrapTurn_columnNeverFalls() {
        // Browser 5 minutes ahead, and the FIRST thing that happens is the human turn — there is nothing to
        // anchor to yet, so it takes the raw client clock. The server event that establishes the skew lands next.
        val rig = Rig(clientNowMs = serverBase + 5 * minute)
        withVm(rig) { vm ->
            vm.onSend("Bist du da?")
            settle()
            awaitRows(vm, 1)

            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "Ja.", complete = true, tsMs = serverBase))
            awaitRows(vm, 2)

            assertEquals(2, vm.transcript.value.size)
            assertNeverFalls(vm.transcript.value)
        }
    }

    // --- 3. clamp: the client clock jumps BACKWARDS between two client rows (NTP correction). ---

    @Test
    fun clientClockJumpsBackwards_columnNeverFalls() {
        val rig = Rig(clientNowMs = serverBase)
        withVm(rig) { vm ->
            rig.bus.tryEmit(AgentEvent.Notice("s-1", "Session gestartet", tsMs = serverBase))
            awaitRows(vm, 1)

            rig.clientNowMs = serverBase + 2 * minute
            vm.onSend("erste")
            settle()
            awaitRows(vm, 2)

            // NTP yanks the wall clock back by ten minutes. The skew is only re-estimated on a SERVER event, so
            // nothing re-anchors here: without the clamp the second turn sinks below the first.
            rig.clientNowMs = serverBase - 8 * minute
            vm.onSend("zweite")
            settle()
            awaitRows(vm, 3)

            assertEquals(3, vm.transcript.value.size)
            assertNeverFalls(vm.transcript.value)
        }
    }

    // --- The skew keeps tracking: a later server event re-estimates it. ---

    @Test
    fun skewIsReEstimated_onEachFreshServerEvent() {
        val rig = Rig(clientNowMs = serverBase)
        withVm(rig) { vm ->
            rig.bus.tryEmit(AgentEvent.Notice("s-1", "start", tsMs = serverBase))
            awaitRows(vm, 1)

            // The client clock now races ahead by an hour; a fresh server event must re-anchor the estimate.
            rig.clientNowMs = serverBase + 60 * minute
            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "x", complete = true, tsMs = serverBase + minute))
            awaitRows(vm, 2)

            rig.clientNowMs = serverBase + 61 * minute
            vm.onSend("und jetzt?")
            settle()
            awaitRows(vm, 3)

            assertNeverFalls(vm.transcript.value)
            val turn = vm.transcript.value[2]
            assertTrue(
                turn.tsMs <= serverBase + 3 * minute,
                "a re-anchored skew must keep the turn near the server's clock, got ${turn.tsMs}",
            )
        }
    }

    // --- A stale server stamp must not drag the estimate backwards. ---

    @Test
    fun resolvedToolCall_carryingItsStartTime_doesNotRegressTheSkew() {
        // StreamJsonMapper re-emits a resolved ToolCall carrying its START time. Taking "the last event's tsMs"
        // as the anchor would drag the skew back to that old instant, and the next client row with it.
        val rig = Rig(clientNowMs = serverBase)
        withVm(rig) { vm ->
            rig.bus.tryEmit(AgentEvent.ToolCall("t-1", "read", "x", ToolStatus.RUNNING, tsMs = serverBase))
            awaitRows(vm, 1)

            rig.clientNowMs = serverBase + 10 * minute
            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "fertig", complete = true, tsMs = serverBase + 10 * minute))
            awaitRows(vm, 2)

            // The resolving tool call arrives LATER but carries the START stamp (the mapper's `prior.copy`).
            rig.clientNowMs = serverBase + 11 * minute
            rig.bus.tryEmit(AgentEvent.ToolCall("t-1", "read", "x", ToolStatus.OK, tsMs = serverBase))
            settle()

            rig.clientNowMs = serverBase + 12 * minute
            vm.onSend("weiter")
            settle()
            awaitRows(vm, 3)

            assertNeverFalls(vm.transcript.value)
        }
    }
}
