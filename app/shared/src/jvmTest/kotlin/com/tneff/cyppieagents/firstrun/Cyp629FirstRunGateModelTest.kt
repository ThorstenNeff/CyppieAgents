package com.tneff.cyppieagents.firstrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-629 — the first-run gate/step model is a set of PURE decisions so the honesty core (fail-closed: unknown ≠
 * configured) is pinned without a compose render. The load-bearing property throughout: a not-yet-loaded or
 * not-`CLONED_OK` status is NEVER treated as "done".
 */
class Cyp629FirstRunGateModelTest {

    private fun status(loaded: Boolean, key: Boolean, clone: CloneStatus) =
        FirstRunConfigStatus(loaded = loaded, apiKeySet = key, cloneStatus = clone)

    @Test
    fun gateMode_transparent_onlyWhenKeySetAndCloned() {
        assertEquals(
            FirstRunGateMode.TRANSPARENT,
            firstRunGateMode(status(loaded = true, key = true, clone = CloneStatus.CLONED_OK)),
            "both prerequisites met → transparent, straight to workspace",
        )
    }

    @Test
    fun gateMode_loading_whenStatusUnknown_failClosed() {
        // The honesty core: an UNLOADED status must be LOADING, never TRANSPARENT — even if the (stale/default)
        // fields happen to read key=true/CLONED_OK. Mutation: drop the `!loaded` guard → this returns TRANSPARENT → red.
        assertEquals(
            FirstRunGateMode.LOADING,
            firstRunGateMode(status(loaded = false, key = true, clone = CloneStatus.CLONED_OK)),
            "unknown status is fail-closed to LOADING, never passed through as done",
        )
        assertEquals(FirstRunGateMode.LOADING, firstRunGateMode(FirstRunConfigStatus.Unknown))
    }

    @Test
    fun gateMode_active_whenUnconfiguredOrPartial() {
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = false, clone = CloneStatus.NOT_CONFIGURED)))
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = true, clone = CloneStatus.CLONING)), "key set but repo cloning → still active")
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = false, clone = CloneStatus.CLONED_OK)), "repo cloned but no key → still active")
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = true, clone = CloneStatus.CLONE_FAILED)), "clone failed → active, never done")
    }

    @Test
    fun openStep_landsOnFirstOpenStep() {
        assertEquals(FirstRunStep.API_KEY, firstRunOpenStep(status(true, key = false, clone = CloneStatus.NOT_CONFIGURED)))
        assertEquals(FirstRunStep.REPO, firstRunOpenStep(status(true, key = true, clone = CloneStatus.CLONING)), "key done → land on repo, not step 1")
        assertEquals(FirstRunStep.REPO, firstRunOpenStep(status(true, key = true, clone = CloneStatus.CLONE_FAILED)))
        assertEquals(FirstRunStep.TEAM, firstRunOpenStep(status(true, key = true, clone = CloneStatus.CLONED_OK)), "both core done → team (optional)")
    }

    @Test
    fun cloneDisplay_mapsStatesFailClosed() {
        assertNull(cloneDisplay(CloneStatus.NOT_CONFIGURED), "repo not set → no clone display")
        assertEquals(CloneDisplay.CLONING, cloneDisplay(CloneStatus.CONFIGURED_NEVER_CLONED))
        assertEquals(CloneDisplay.CLONING, cloneDisplay(CloneStatus.CLONING))
        assertEquals(CloneDisplay.CLONED_OK, cloneDisplay(CloneStatus.CLONED_OK))
        assertEquals(CloneDisplay.FAILED, cloneDisplay(CloneStatus.CLONE_FAILED))
    }

    @Test
    fun decodeCloneStatus_absentIsNeverCloned_failClosed() {
        // Old server / missing field → never CLONED_OK. Configured → never-cloned; not → not-configured.
        assertEquals(CloneStatus.CONFIGURED_NEVER_CLONED, decodeCloneStatus(raw = null, repoConfigured = true))
        assertEquals(CloneStatus.NOT_CONFIGURED, decodeCloneStatus(raw = null, repoConfigured = false))
        // A present value passes through unchanged.
        assertEquals(CloneStatus.CLONED_OK, decodeCloneStatus(raw = CloneStatus.CLONED_OK, repoConfigured = true))
    }

    @Test
    fun terminalCloneStatus_onlyOkOrFailed() {
        assertTrue(isTerminalCloneStatus(CloneStatus.CLONED_OK))
        assertTrue(isTerminalCloneStatus(CloneStatus.CLONE_FAILED))
        // A long clone is NOT terminal — the client never times a CLONING out into a failure (§4.4).
        assertTrue(!isTerminalCloneStatus(CloneStatus.CLONING))
        assertTrue(!isTerminalCloneStatus(CloneStatus.CONFIGURED_NEVER_CLONED))
        assertTrue(!isTerminalCloneStatus(CloneStatus.NOT_CONFIGURED))
    }
}
