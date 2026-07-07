package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.net.Backoff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-289 guard (QA, atomic with the EventTail AccessRevoked fix) — a **terminal** reject must NOT reconnect.
 *
 * `/ws/events` is operator-only and closes with 1008 (VIOLATED_POLICY) on a missing/invalid/revoked operator
 * token, mapped to [EventLiveEvent.AccessRevoked] as the LAST event before the source completes (see
 * `EventsWsClientE2eTest.close1008_mapsToAccessRevoked`). CYP-289 wrapped the tail in `.reconnecting()`, which
 * re-subscribes on ANY completion — so without a terminal-stop the collector loops forever on a revoked access
 * (measured 1001 re-subscriptions in 1s of virtual time at a 1ms backoff), hammering the operator endpoint. A
 * 1008 is terminal (access won't return without re-auth), so the VM must STOP reconnecting on AccessRevoked
 * (cancel the collect) — this pins that invariant so the `.reconnecting()` wrap can never silently regress it.
 */
class EventTailAccessRevokedTest {

    /** Source: Connected → AccessRevoked → complete — exactly the real 1008 path (flow ends with AccessRevoked). */
    private class AccessRevokedThenComplete : EventLiveSource {
        val subscriptions = MutableStateFlow(0)
        override fun events(filter: EventFilter): Flow<EventLiveEvent> = flow {
            subscriptions.value += 1
            emit(EventLiveEvent.Connected)
            emit(EventLiveEvent.AccessRevoked)
        }
    }

    @Test
    fun accessRevoked_terminates_doesNotReconnectLoop() = runTest {
        val source = AccessRevokedThenComplete()
        EventTailViewModel(source, backoff = Backoff(initialMs = 1, maxMs = 1), scope = backgroundScope) // init auto-starts collect
        // Advance well past many backoff intervals; a terminal AccessRevoked must never re-subscribe.
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(1, source.subscriptions.value, "AccessRevoked (1008) is terminal — subscriptions>1 means a reconnect LOOP")
    }
}
