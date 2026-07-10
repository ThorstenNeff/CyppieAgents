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
 * CYP-335 — how the two rows that are BORN in this client ([AgentEvent.UserTurn], the `conn-error`
 * [AgentEvent.Notice]) get their timestamp. Every other row is dated by the server.
 *
 * **What is promised here, exactly:**
 *  - a client-born row carries the client's *current* time, never an inference from event history;
 *  - a client-born row never renders below the row above it.
 *
 * **What is NOT promised:** that the whole column ascends. A server row is folded with its raw `tsMs` — a
 * server stamp is a fact and is never clamped. With a browser running fast the column **inverts**: the agent's
 * reply renders beneath your question with an earlier time. Precisely: *the column never falls as long as the
 * client's clock error against the server does not grow between a client row and the next server row.* On
 * `localhost` that case is unreachable. Closing it needs `serverNowMs` at attach — **CYP-346**, server +
 * `:core`, not this module.
 *
 * The invariant "the column never falls" was the trap here: it is necessary and **not sufficient**. An estimate
 * derived from event timestamps keeps the column ascending while making every stamp hours wrong (see
 * [replayedOldHistory_doesNotBackdateANewTurn]). A suite that only checks monotonicity waves that through.
 *
 * The two `cyp346_` tests below **characterise the accepted defect**. They are written to go red when CYP-346
 * lands, forcing an update rather than rotting silently — an `@Ignore` would not. And every assertion here is
 * on **behaviour** (where does the stamp land), never on the mechanism: a test asserting "no skew field exists"
 * would fight the very fix CYP-346 is going to bring.
 */
@OptIn(ExperimentalTestApi::class)
class AgentClientStampTest {

    private val agentId = "backend"
    private val minute = 60_000L

    /** 2026-07-09T20:00:00Z. */
    private val serverBase = 1_783_627_200_000L

    /** A hand-driven session + a hand-driven client clock. */
    private class Rig(var clientNowMs: Long) {
        val bus = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
        val session = object : AgentSession {
            override val events: Flow<AgentEvent> = bus
            override fun sendMessage(text: String) {}
        }
    }

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

    private class ComposeRunner(private val test: androidx.compose.ui.test.ComposeUiTest) {
        fun settle() = test.waitForIdle()
        fun awaitRows(vm: AgentViewModel, count: Int) {
            test.waitUntil(timeoutMillis = 5_000L) { vm.transcript.value.size >= count }
        }
    }

    // --- The regression that killed the skew estimate. Must go red if anyone re-derives it from events. ---

    @Test
    fun replayedOldHistory_doesNotBackdateANewTurn() {
        // The operator opens the window at 09:02 with a PERFECTLY CORRECT browser clock. The agent has been
        // silent since yesterday 22:14, and the first connect replays that history (`AgentWsClient` omits
        // `since` on the first connect). Then the operator types.
        //
        // `skew = lastEventTs − clientNow` would read −11 h here and stamp the turn `22:14`. An event timestamp
        // is only a LOWER BOUND on the server's now: from the client, "browser 5 min fast" and "last event 5 min
        // old" are the same observation. The error grows with idle time — and the column still ascends, so a
        // monotonicity assertion never notices.
        //
        // **This test is one half of a pair, and only the pair is unambiguous.** Its partner,
        // [cyp346_fastBrowser_invertsTheColumn_replyRendersAboveTheQuestion], also goes red if someone
        // re-introduces the skew — but for the *wrong reason*: the skew removes the dip it characterises, so it
        // cannot tell "CYP-346 landed cleanly" from "a skew crept back in". THIS test is what separates the two:
        // a re-derived skew backdates the turn here, a server-anchored stamp does not. Delete either one and the
        // other becomes ambiguous. Keep them together.
        val yesterdayEvening = serverBase
        val thisMorning = serverBase + 11 * 60 * minute
        val rig = Rig(clientNowMs = thisMorning)
        withVm(rig) { vm ->
            rig.bus.tryEmit(AgentEvent.AssistantText("a-old", "gestern", complete = true, tsMs = yesterdayEvening))
            awaitRows(vm, 1)

            vm.onSend("guten morgen")
            settle()
            awaitRows(vm, 2)

            // Assert the BEHAVIOUR (the stamp lands at ~now), not the absence of a mechanism. An assertion like
            // "there is no skew field" would be red when CYP-346 lands correctly — it would fight the real fix.
            // A tolerance keeps this green for any stamping strategy that dates the turn honestly, including a
            // future server-anchored one.
            val turn = vm.transcript.value[1]
            assertTrue(
                turn.tsMs >= thisMorning - minute,
                "a turn typed at ${formatLocalHhMm(thisMorning)} must be dated ~now, but carries " +
                    "${formatLocalHhMm(turn.tsMs)} — an age inferred from the last event",
            )
        }
    }

    @Test
    fun connErrorNotice_alsoCarriesTheCurrentClientTime() {
        val thisMorning = serverBase + 11 * 60 * minute
        val failing = object : AgentSession {
            override val events: Flow<AgentEvent> = kotlinx.coroutines.flow.flow { throw IllegalStateException("fatal") }
            override fun sendMessage(text: String) {}
        }
        runComposeUiTest {
            lateinit var vm: AgentViewModel
            setContent {
                MaterialTheme {
                    vm = remember { AgentViewModel(failing, agentId, nowMs = { thisMorning }) }
                    AgentWindow(agentId = agentId, viewModel = vm)
                }
            }
            waitUntil(timeoutMillis = 5_000L) { vm.transcript.value.isNotEmpty() }
            // Same tolerance as [replayedOldHistory_doesNotBackdateANewTurn], for the same reason: this is a
            // CLIENT-BORN stamp, and an exact equality would fight CYP-346's server-anchored one over a few ms.
            val notice = vm.transcript.value.single()
            assertTrue(
                notice.tsMs >= thisMorning - minute,
                "the connection-loss notice must be dated ~now (${formatLocalHhMm(thisMorning)}), " +
                    "but carries ${formatLocalHhMm(notice.tsMs)}",
            )
        }
    }

    // --- The clamp: the client's own clock is not monotonic. ---

    @Test
    fun clientClockJumpsBackwards_clientRowsNeverFall() {
        val rig = Rig(clientNowMs = serverBase)
        withVm(rig) { vm ->
            rig.bus.tryEmit(AgentEvent.Notice("s-1", "Session gestartet", tsMs = serverBase))
            awaitRows(vm, 1)

            rig.clientNowMs = serverBase + 2 * minute
            vm.onSend("erste")
            settle()
            awaitRows(vm, 2)

            // NTP yanks the wall clock back by ten minutes (doc 06 §6: wall-clock time is not monotonic).
            // Without the clamp the second turn sinks below the first.
            rig.clientNowMs = serverBase - 8 * minute
            vm.onSend("zweite")
            settle()
            awaitRows(vm, 3)

            val stamps = vm.transcript.value.map { it.tsMs }
            assertTrue(
                stamps[2] >= stamps[1],
                "a client row never renders below the row above it; got $stamps",
            )
        }
    }

    // --- The accepted defect, characterised. These MUST go red when CYP-346 lands. ---

    @Test
    fun cyp346_fastBrowser_invertsTheColumn_replyRendersAboveTheQuestion() {
        // Characterisation, not endorsement. Browser 5 minutes fast: the turn is stamped by the client, the
        // reply by the server, and the column INVERTS — the agent's answer carries an earlier time than the
        // question it answers. This is the accepted cost of not guessing the server's clock (class KDoc): the
        // error is bounded by the clock skew instead of by the agent's idle time.
        //
        // When CYP-346 lands (`serverNowMs` at attach), the dip disappears and this test fails. That is the
        // point: it forces an update instead of rotting.
        //
        // **But it fails for the same reason if someone re-introduces the event-derived skew** — that also
        // removes the dip. This test alone therefore CANNOT tell a clean CYP-346 from a smuggled-back estimate.
        // Its partner [replayedOldHistory_doesNotBackdateANewTurn] is what separates them: a re-derived skew
        // backdates a turn typed after a stale replay, a server-anchored stamp does not. Read both red/green
        // results together, and do not delete one without the other — alone, each is ambiguous.
        val rig = Rig(clientNowMs = serverBase + 5 * minute)
        withVm(rig) { vm ->
            rig.bus.tryEmit(AgentEvent.Notice("s-1", "start", tsMs = serverBase))
            awaitRows(vm, 1)

            vm.onSend("frage")
            settle()
            awaitRows(vm, 2)

            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "antwort", complete = true, tsMs = serverBase + minute))
            awaitRows(vm, 3)

            // The hint belongs on the FIRST assertion that CYP-346 will break, not the last: the ones after it
            // never run, so their message is never printed. Whoever reads the red result in three months must see
            // what to do, not two bare numbers.
            val stamps = vm.transcript.value.map { it.tsMs }
            val cyp346Hint = "If this now fails, CYP-346 probably landed (the client stamp is server-anchored): " +
                "delete this characterisation test and assert the column ascends instead. If it did NOT land, " +
                "someone re-derived a skew from event timestamps — see replayedOldHistory_doesNotBackdateANewTurn."
            assertEquals(
                serverBase + 5 * minute,
                stamps[1],
                "the turn carries the (fast) client clock. $cyp346Hint",
            )
            assertEquals(
                serverBase + minute,
                stamps[2],
                "the reply carries the server stamp, unclamped — a fact. $cyp346Hint",
            )
            assertTrue(
                stamps[2] < stamps[1],
                "CYP-346 characterisation: the reply still renders below the question it answers ($stamps). $cyp346Hint",
            )
        }
    }

    @Test
    fun cyp346_bootstrapTurn_beforeAnyServerEvent_canSitAboveTheFirstServerRow() {
        // The same defect from the other side: the first turn in a window precedes every server event, so it
        // takes the client clock outright. A slow-to-arrive (or older) server row then lands beneath it.
        // Also goes red once CYP-346 anchors the client stamp to the server.
        val rig = Rig(clientNowMs = serverBase + 5 * minute)
        withVm(rig) { vm ->
            vm.onSend("bist du da?")
            settle()
            awaitRows(vm, 1)

            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "ja", complete = true, tsMs = serverBase))
            awaitRows(vm, 2)

            val stamps = vm.transcript.value.map { it.tsMs }
            assertTrue(
                stamps[1] < stamps[0],
                "CYP-346 characterisation: the bootstrap turn still sits above the first server row ($stamps). " +
                    "If this now fails, CYP-346 probably landed (the client stamp is server-anchored): delete " +
                    "this characterisation test and assert the column ascends instead.",
            )
        }
    }
}
