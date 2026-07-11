package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-396 T-A (the core tooth) — the status dot's form+role is a PURE decision ([statusDotSpec]), so both are
 * pinned without a pixel compare. The fix: `UNKNOWN` = RING/OUTLINE — a different AXIS from `STOPPED`, never a
 * pale disc that looks like it. Discriminating: it names the wrong implementations this test rejects, not "does
 * it render".
 */
class Cyp396StatusDotSpecTest {

    @Test
    fun unknownIsRingOutline_theFix() {
        // Mutation (a) UNKNOWN -> FILL/OUTLINE (looks like STOPPED) ⇒ red; (b) UNKNOWN -> RING/<other role> ⇒ red.
        assertEquals(StatusDotShape.RING to StatusDotRole.OUTLINE, statusDotSpec(AgentLifecycleState.UNKNOWN, pending = false))
    }

    @Test
    fun otherStatesAreFilledDiscsWithTheirRole() {
        assertEquals(StatusDotShape.FILL to StatusDotRole.PRIMARY, statusDotSpec(AgentLifecycleState.RUNNING, pending = false))
        assertEquals(StatusDotShape.FILL to StatusDotRole.OUTLINE, statusDotSpec(AgentLifecycleState.STOPPED, pending = false))
        assertEquals(StatusDotShape.FILL to StatusDotRole.ERROR, statusDotSpec(AgentLifecycleState.ERROR, pending = false))
    }

    @Test
    fun pendingIsAlwaysFilledNeutral_forEveryState() {
        AgentLifecycleState.entries.forEach { state ->
            assertEquals(
                StatusDotShape.FILL to StatusDotRole.NEUTRAL,
                statusDotSpec(state, pending = true),
                "pending (Startet…/Neustart…) must win over $state",
            )
        }
    }

    @Test
    fun unknownNeverCollapsesOntoStoppedOrRunning_onTheRenderAxis() {
        val unknown = statusDotSpec(AgentLifecycleState.UNKNOWN, pending = false)
        // The render-axis "never-resolves": UNKNOWN's appearance differs from STOPPED and RUNNING — in SHAPE.
        assertNotEquals(statusDotSpec(AgentLifecycleState.STOPPED, pending = false), unknown)
        assertNotEquals(statusDotSpec(AgentLifecycleState.RUNNING, pending = false), unknown)
        assertEquals(StatusDotShape.RING, unknown.first)
        assertEquals(StatusDotShape.FILL, statusDotSpec(AgentLifecycleState.STOPPED, pending = false).first)
    }
}
