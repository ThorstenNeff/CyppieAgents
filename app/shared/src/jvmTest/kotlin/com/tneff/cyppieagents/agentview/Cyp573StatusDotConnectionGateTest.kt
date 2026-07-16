package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.comm.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-573 (Bug, status-dot-honesty) — the load-bearing tooth for the connection-gate, a PURE decision
 * ([gatedLifecycleState]) so it is deterministic without a compose render.
 *
 * The bug: the header status dot/label render the last-known lifecycle [AgentLifecycleState] unconditionally.
 * [AgentLifecycleLiveSource] holds that state across a WS drop (a reconnect just re-streams the snapshot and upserts),
 * so across a real server/hub restart the dot keeps showing a **stale RUNNING for an already-stopped agent** through
 * the reconnect gap — a claim it can no longer prove. The fix fails closed: while the live feed is not
 * [ConnectionStatus.LIVE], the resolved state collapses to [AgentLifecycleState.UNKNOWN].
 *
 * Discriminating (names the wrong impls it rejects): a fix that only gates the DOT SHAPE but leaves the label reading
 * "Läuft" would still pass a shape-only test — this asserts the single gated STATE that drives BOTH label and dot, so
 * an ungated label can't slip through. Removing the `connection == LIVE` guard reddens every gated case.
 */
class Cyp573StatusDotConnectionGateTest {

    @Test
    fun resolvedStateShows_onlyWhenLive() {
        // The honest path: a LIVE feed may assert the real state — for every resolved state, unchanged.
        for (state in listOf(AgentLifecycleState.RUNNING, AgentLifecycleState.STOPPED, AgentLifecycleState.ERROR)) {
            assertEquals(
                state,
                gatedLifecycleState(state, pending = false, connection = ConnectionStatus.LIVE),
                "a LIVE feed must show the resolved $state unchanged",
            )
        }
    }

    @Test
    fun staleRunning_collapsesToUnknown_whenNotLive() {
        // THE bug: stopped-agent RUNNING held across the reconnect gap. Not-LIVE ⇒ UNKNOWN, never a phantom RUNNING.
        // Mutation: drop the connection guard ⇒ returns RUNNING ⇒ red.
        assertEquals(
            AgentLifecycleState.UNKNOWN,
            gatedLifecycleState(AgentLifecycleState.RUNNING, pending = false, connection = ConnectionStatus.DISCONNECTED),
            "a WS drop must fail closed to UNKNOWN, never keep asserting RUNNING",
        )
        // CONNECTING (reconnect in flight) is also non-LIVE → UNKNOWN, in lockstep with the ReconnectingChip.
        assertEquals(
            AgentLifecycleState.UNKNOWN,
            gatedLifecycleState(AgentLifecycleState.RUNNING, pending = false, connection = ConnectionStatus.CONNECTING),
            "a reconnect-in-flight feed cannot prove RUNNING → UNKNOWN",
        )
    }

    @Test
    fun everyResolvedState_gatesToUnknown_whileNotLive() {
        for (state in listOf(AgentLifecycleState.RUNNING, AgentLifecycleState.STOPPED, AgentLifecycleState.ERROR)) {
            for (down in listOf(ConnectionStatus.DISCONNECTED, ConnectionStatus.CONNECTING)) {
                assertEquals(
                    AgentLifecycleState.UNKNOWN,
                    gatedLifecycleState(state, pending = false, connection = down),
                    "$state must gate to UNKNOWN while the feed is $down",
                )
            }
        }
    }

    @Test
    fun pending_isNotGated_becauseItIsAClientLocalTransientIntent() {
        // Startet…/Neustart… is surfaced by the label branch and always resolves on the next lifecycle event; it is
        // not a resolved-state claim, so the gate passes it through even on a down feed (the label shows the transient).
        assertEquals(
            AgentLifecycleState.RUNNING,
            gatedLifecycleState(AgentLifecycleState.RUNNING, pending = true, connection = ConnectionStatus.DISCONNECTED),
            "pending is a transient intent, not gated — the label surfaces Startet…/Neustart…",
        )
    }
}
