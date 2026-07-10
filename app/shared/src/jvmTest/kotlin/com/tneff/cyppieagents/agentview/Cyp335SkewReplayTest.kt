package com.tneff.cyppieagents.agentview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-335 (QA) — **the skew estimator conflates clock offset with event age.**
 *
 * `AgentViewModel.observeServerClock` estimates `skew = serverTs − clientNow` from the most recent server event
 * it has seen. That is sound for an event that arrives *live*: its `tsMs` was minted moments ago, so the
 * difference really is the offset between the two clocks.
 *
 * It is **not** sound for a **replayed** event. On the first connect the server replays the whole persisted
 * history (`AgentWsClient.agentUrl()` omits `?since` when `lastSeq < 0`), so after a page reload the newest
 * event the client sees may be minutes or hours old. Its `tsMs − now` is not the clock offset — it is the
 * **age of the transcript**. The estimator cannot tell the two apart, and every subsequently client-born row
 * (`UserTurn`, the `conn-error` `Notice`) is then stamped that far in the past.
 *
 * The monotonicity invariant does **not** catch this: the column still never falls, because the mis-stamped row
 * is clamped to the row above it. It reads as a perfectly ordered transcript in which your own message claims to
 * have been sent an hour before you typed it — the "timestamp that lies" this story set out to eliminate, one
 * level up.
 *
 * The two tests below therefore assert the **stamp**, not the ordering. They pin the one thing the invariant
 * cannot see.
 */
@OptIn(ExperimentalTestApi::class)
class Cyp335SkewReplayTest {

    private val agentId = "backend"
    private val minute = 60_000L
    private val hour = 60 * minute

    /** 2026-07-09T20:00:00Z. */
    private val now = 1_783_627_200_000L

    private class Rig(var clientNowMs: Long) {
        val bus = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
        val session = object : AgentSession {
            override val events: Flow<AgentEvent> = bus
            override fun sendMessage(text: String) {}
        }
    }

    private fun withVm(rig: Rig, body: (AgentViewModel, () -> Unit, (Int) -> Unit) -> Unit) = runComposeUiTest {
        lateinit var vm: AgentViewModel
        setContent {
            MaterialTheme {
                vm = remember { AgentViewModel(rig.session, agentId, nowMs = { rig.clientNowMs }) }
                AgentWindow(agentId = agentId, viewModel = vm)
            }
        }
        body(
            vm,
            { waitForIdle() },
            { count -> waitUntil(timeoutMillis = 5_000L) { vm.transcript.value.size >= count } },
        )
    }

    /**
     * Reload on an agent whose transcript is an hour old. The two clocks **agree** — the true skew is zero — so
     * a turn typed now must be stamped now.
     */
    @Test
    fun reloadOnStaleHistory_userTurnIsStampedNow_notAtTheAgeOfTheTranscript() {
        // Client and server clocks are identical. Any deviation the VM introduces is its own doing.
        val rig = Rig(clientNowMs = now)
        withVm(rig) { vm, settle, awaitRows ->
            // First connect after a reload: the server replays the whole persisted history. These stamps are old.
            rig.bus.tryEmit(AgentEvent.Notice("s-1", "Session gestartet", tsMs = now - 2 * hour))
            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "Erledigt.", complete = true, tsMs = now - hour))
            awaitRows(2)

            // The operator types a message. Right now. The clocks agree, so this row belongs at `now`.
            vm.onSend("Und jetzt?")
            settle()

            val turn = vm.transcript.value.last()
            assertTrue(turn is AgentEvent.UserTurn, "the last row is the composer's turn")
            val offBy = now - turn.tsMs
            assertTrue(
                offBy < minute,
                "a turn typed at ${formatLocalHhMm(now)} must not be stamped ${formatLocalHhMm(turn.tsMs)} " +
                    "(${offBy / minute} min in the past). The skew estimator absorbed the AGE of the replayed " +
                    "history instead of the offset between the clocks: skew = staleServerTs − now.",
            )
        }
    }

    /**
     * The same defect, expressed as the operator sees it: after the reload the agent answers live. Their reply is
     * stamped `now` by the server. The turn that *provoked* it claims to be an hour older.
     */
    @Test
    fun reloadOnStaleHistory_turnAndItsReplyAreNotAnHourApart() {
        val rig = Rig(clientNowMs = now)
        withVm(rig) { vm, settle, awaitRows ->
            rig.bus.tryEmit(AgentEvent.AssistantText("a-1", "Erledigt.", complete = true, tsMs = now - hour))
            awaitRows(1)

            vm.onSend("Und jetzt?")
            settle()

            // The agent replies immediately; the server stamps it with the current wall clock.
            rig.bus.tryEmit(AgentEvent.AssistantText("a-2", "Sofort.", complete = true, tsMs = now + 1_000L))
            awaitRows(3)

            val turn = vm.transcript.value[1]
            val reply = vm.transcript.value[2]
            val gap = reply.tsMs - turn.tsMs
            assertTrue(
                gap < minute,
                "the agent replied a second after the turn, but the rendered gap is ${gap / minute} min " +
                    "(turn ${formatLocalHhMm(turn.tsMs)}, reply ${formatLocalHhMm(reply.tsMs)}). " +
                    "The column never falls — and still lies.",
            )
        }
    }
}
