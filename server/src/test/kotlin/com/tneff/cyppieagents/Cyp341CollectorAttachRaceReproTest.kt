package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.SystemEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-341 (test-only flake) — DETERMINISTIC reconstruction of the collector-attach race that reddens
 * `Cyp330RestartRobustnessTest.restartWithStaleResume_healsToFreshWithoutATurn` under a host-load spike.
 *
 * **Real shape:** `ResumingSession._events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256)` →
 * **replay = 0**. The flaky test launches its collector on `Dispatchers.Default`
 * (`scope.launch { session.events.collect { seen.add(it) } }`, line 115) and only asserts
 * `seen.any { it is SystemEvent }` (line 126) AFTER `sendTurn` (line 125) — during which the freshly-respawned
 * session emits its `system/init`. If a load spike starves `Dispatchers.Default` and delays the collector's
 * dispatch **past** that emit, the replay=0 flow drops the event → the assertion reds.
 *
 * The empirical window is narrow (4/4 loaded reruns stayed green), so this reconstructs the exact loss
 * DETERMINISTICALLY by ordering — attaching the collector artificially BEHIND the emission (= the load outcome),
 * against the EXACT flow config the real session uses.
 *
 * **Fix direction (Backend hardening — NOT a timeout widen):** make the collector deterministic vs the emit —
 * subscribe BEFORE the session can emit (attach the collector before `start()`/the first turn, or await the
 * first event), or give the flow `replay = 1`. Both close the race ([fixA_subscribeBeforeEmit],
 * [fixB_replayOne]). Widening the 15s timeout would NOT help — the event is already gone, not late.
 */
class Cyp341CollectorAttachRaceReproTest {

    private fun systemInit() = SystemEvent(subtype = "init", sessionId = "fresh-sid")

    /** THE BUG: replay=0 + subscribe-AFTER-emit → the SystemEvent is lost (the flake's failure mode). */
    @Test
    fun theBug_lateSubscriberOnReplayZeroFlow_losesTheSystemEvent() = runBlocking {
        val events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256) // EXACT ResumingSession config → replay=0
        // The load outcome: the fresh session emits system/init BEFORE the delayed collector subscribes.
        events.emit(systemInit()) // completes immediately (no subscribers, replay=0 → value discarded)
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        val job = launch { events.collect { seen.add(it) } }
        // Ample time — the point is the event is already GONE, not that the collector is merely slow.
        withTimeoutOrNull(1_000) { while (seen.isEmpty()) delay(10) }
        job.cancel()
        assertFalse(
            seen.any { it is SystemEvent },
            "replay=0 + subscribe-after-emit → the SystemEvent is LOST — this is exactly what the CYP-341 flake hits",
        )
    }

    /** FIX A (test-side): subscribe BEFORE the emit (attach the collector before start / await the first event). */
    @Test
    fun fixA_subscribeBeforeEmit_catchesIt() = runBlocking {
        val events = MutableSharedFlow<StreamJsonEvent>(extraBufferCapacity = 256) // still replay=0
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        val job = launch { events.collect { seen.add(it) } }
        events.subscriptionCount.first { it >= 1 } // deterministically wait until the collector is actually subscribed
        events.emit(systemInit())
        withTimeoutOrNull(2_000) { while (seen.none { it is SystemEvent }) delay(10) }
        job.cancel()
        assertTrue(seen.any { it is SystemEvent }, "collector subscribed BEFORE the emit → the SystemEvent is caught (race closed)")
    }

    /** FIX B (product-side): replay=1 lets even a late subscriber replay the last event. */
    @Test
    fun fixB_replayOneFlow_lateSubscriberStillCatchesIt() = runBlocking {
        val events = MutableSharedFlow<StreamJsonEvent>(replay = 1, extraBufferCapacity = 256) // replay=1
        events.emit(systemInit()) // retained for future subscribers
        val seen = CopyOnWriteArrayList<StreamJsonEvent>()
        val job = launch { events.collect { seen.add(it) } }
        withTimeoutOrNull(2_000) { while (seen.none { it is SystemEvent }) delay(10) }
        job.cancel()
        assertTrue(seen.any { it is SystemEvent }, "replay=1 → a late subscriber still replays system/init (race closed)")
    }
}
