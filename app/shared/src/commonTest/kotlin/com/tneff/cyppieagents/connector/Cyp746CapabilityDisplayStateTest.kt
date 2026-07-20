package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.agentview.AgentLifecycleState
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.CapabilityStatus
import com.tneff.cyppieagents.model.ConnectorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-746 — the five-state capability display derivation (UIUX §5 honesty teeth). All states are client-derivable
 * from caps + loading + [AgentLifecycleState]; these pin the honesty invariants without a Compose render.
 */
class Cyp746CapabilityDisplayStateTest {

    /** UIUX §5.1 — the regression to CYP-742: a hung load must NEVER look like full fidelity; it falls to D. */
    @Test
    fun hungLoading_isUnknown_notFull() {
        val s = capabilityDisplayState(caps = null, loading = true, loadingTimedOut = true, lifecycle = AgentLifecycleState.RUNNING)
        assertEquals(CapabilityDisplayState.UNKNOWN, s, "a load hung past the threshold falls to UNKNOWN (honest), never lingers as loading")
        assertNotEquals(CapabilityDisplayState.FULL, s, "a hung load must NEVER render as full fidelity (the CYP-742 regression)")
    }

    /** UIUX §5.2 — D unknown (null+RUNNING) and E not-started (null+≠RUNNING) are DISTINCT states. */
    @Test
    fun dUnknown_vs_eNotStarted_areDistinct() {
        val d = capabilityDisplayState(null, loading = false, loadingTimedOut = false, AgentLifecycleState.RUNNING)
        val e = capabilityDisplayState(null, loading = false, loadingTimedOut = false, AgentLifecycleState.STOPPED)
        assertEquals(CapabilityDisplayState.UNKNOWN, d, "null caps + RUNNING = D (unknown — should have reported and hasn't)")
        assertEquals(CapabilityDisplayState.NOT_STARTED, e, "null caps + not-RUNNING = E (not started / didn't report at start)")
        assertNotEquals(d, e, "D and E must be distinct states (the lifecycle dot carries the visual)")
    }

    /** UIUX §5.4 — full fidelity stays badge-free (the FULL state) — no CYP-280 regression. */
    @Test
    fun fullFidelity_isFull() {
        assertEquals(CapabilityDisplayState.FULL, capabilityDisplayState(FULL_CAPS, loading = false, loadingTimedOut = false, AgentLifecycleState.RUNNING))
    }

    @Test
    fun degradedCaps_isRestricted() {
        assertEquals(CapabilityDisplayState.RESTRICTED, capabilityDisplayState(DEGRADED_CAPS, loading = false, loadingTimedOut = false, AgentLifecycleState.RUNNING))
    }

    /** UIUX §5.5 — caps==null NEVER renders as FULL/RESTRICTED (no dimension claimed available without caps). */
    @Test
    fun capsNull_neverFullOrRestricted() {
        for (loading in listOf(true, false)) {
            for (timedOut in listOf(true, false)) {
                for (lc in AgentLifecycleState.entries) {
                    val s = capabilityDisplayState(null, loading, timedOut, lc)
                    assertTrue(
                        s != CapabilityDisplayState.FULL && s != CapabilityDisplayState.RESTRICTED,
                        "caps==null must never be FULL/RESTRICTED (got $s for loading=$loading timedOut=$timedOut lc=$lc)",
                    )
                }
            }
        }
    }

    @Test
    fun loadingBeforeTimeout_isLoading_regardlessOfLifecycle() {
        // C is time-bounded, but BEFORE the threshold an in-flight load is LOADING (not prematurely settled to D/E).
        assertEquals(CapabilityDisplayState.LOADING, capabilityDisplayState(null, loading = true, loadingTimedOut = false, AgentLifecycleState.RUNNING))
        assertEquals(CapabilityDisplayState.LOADING, capabilityDisplayState(null, loading = true, loadingTimedOut = false, AgentLifecycleState.STOPPED))
    }

    /**
     * CYP-746 coupling tooth — the badge's C→D timeout must be **≥ the CYP-742 re-poll duration**. Otherwise the
     * badge flips LOADING→UNKNOWN *before* the bounded re-poll (attempts × interval) can land caps → a false-unknown
     * `○` flash on a live-created agent that is about to report. Lower the threshold below the re-poll bound → RED.
     */
    @Test
    fun checkingTimeout_isAtLeastRepollDuration_noFalseUnknownFlash() {
        assertTrue(
            CAPS_CHECKING_TIMEOUT_MS >= CAPS_REPOLL_MAX_DURATION_MS,
            "C→D timeout ($CAPS_CHECKING_TIMEOUT_MS ms) must be ≥ the CYP-742 re-poll duration " +
                "($CAPS_REPOLL_MAX_DURATION_MS ms) — else the badge flashes a false `○` before caps can land",
        )
    }

    /**
     * CYP-742 clean-Done — the **unknown→reported transition**, not merely the endpoints. While the agent stays
     * RUNNING, when the re-poll lands caps (null→present) the display MUST leave UNKNOWN for a reported verdict, so
     * the badge switches `○`→reported. Pins the CHANGE across the SAME held lifecycle (two evaluations differ),
     * distinct from the per-state teeth above.
     */
    @Test
    fun unknownToReported_transition_whileRunning_notJustEndpoints() {
        val lc = AgentLifecycleState.RUNNING
        val before = capabilityDisplayState(caps = null, loading = false, loadingTimedOut = true, lifecycle = lc)
        val afterFull = capabilityDisplayState(caps = FULL_CAPS, loading = false, loadingTimedOut = true, lifecycle = lc)
        val afterDegraded = capabilityDisplayState(caps = DEGRADED_CAPS, loading = false, loadingTimedOut = true, lifecycle = lc)
        assertEquals(CapabilityDisplayState.UNKNOWN, before, "start: null caps while RUNNING = D (the `○` badge)")
        assertNotEquals(before, afterFull, "caps landing must move the display OFF unknown (the ○→reported transition)")
        assertNotEquals(before, afterDegraded, "caps landing degraded must also leave unknown (○→`!`)")
        assertEquals(CapabilityDisplayState.FULL, afterFull, "full caps → FULL (badge disappears)")
        assertEquals(CapabilityDisplayState.RESTRICTED, afterDegraded, "degraded caps → RESTRICTED (`!`)")
    }

    private companion object {
        val FULL_CAPS = Capabilities(
            CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE,
            CapabilityStatus.AVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.STREAM_JSON,
        )
        val DEGRADED_CAPS = Capabilities(
            CapabilityStatus.AVAILABLE, CapabilityStatus.LIMITED, CapabilityStatus.AVAILABLE,
            CapabilityStatus.UNAVAILABLE, CapabilityStatus.AVAILABLE, ConnectorKind.MCP,
        )
    }
}
