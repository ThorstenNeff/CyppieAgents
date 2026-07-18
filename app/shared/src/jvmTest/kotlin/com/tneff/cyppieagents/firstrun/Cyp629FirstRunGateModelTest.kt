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
    fun gateMode_transparent_whenConfigured_notRequiringCloneOk() {
        // ★ B1 (CYP-629 live-wiring): TRANSPARENT on CONFIGURED (key set AND repo SET), NOT on CLONED_OK — there is no
        // backend clone-status source, so the client never produces CLONED_OK; gating on it would hang the wizard
        // forever. Mutation: require `cloneStatus == CLONED_OK` again → the never-cloned case reddens (a configured hub
        // stuck ACTIVE). This also STRUCTURALLY guarantees the leak invariant: CLONED_OK is never needed → never faked.
        assertEquals(
            FirstRunGateMode.TRANSPARENT,
            firstRunGateMode(status(loaded = true, key = true, clone = CloneStatus.CONFIGURED_NEVER_CLONED)),
            "key set AND repo set (never cloned) → transparent: the clone is best-effort, never a gate",
        )
        assertEquals(
            FirstRunGateMode.TRANSPARENT,
            firstRunGateMode(status(loaded = true, key = true, clone = CloneStatus.CLONED_OK)),
            "key set AND repo cloned → transparent too",
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
        // B1: ACTIVE ⟺ NOT configured = no key OR repo NOT set (NOT_CONFIGURED). A set repo is configured (transparent).
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = false, clone = CloneStatus.NOT_CONFIGURED)), "nothing set → active")
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = true, clone = CloneStatus.NOT_CONFIGURED)), "key set but repo NOT set → active (repo step open)")
        assertEquals(FirstRunGateMode.ACTIVE, firstRunGateMode(status(true, key = false, clone = CloneStatus.CONFIGURED_NEVER_CLONED)), "repo set but no key → active")
    }

    @Test
    fun openStep_landsOnFirstOpenStep() {
        assertEquals(FirstRunStep.API_KEY, firstRunOpenStep(status(true, key = false, clone = CloneStatus.NOT_CONFIGURED)))
        assertEquals(FirstRunStep.REPO, firstRunOpenStep(status(true, key = true, clone = CloneStatus.NOT_CONFIGURED)), "key done, repo NOT set → land on repo, not step 1")
        // B1: a set-but-uncloned repo is done (clone is not a prerequisite) → team, matching firstRunGateMode's TRANSPARENT.
        assertEquals(FirstRunStep.TEAM, firstRunOpenStep(status(true, key = true, clone = CloneStatus.CONFIGURED_NEVER_CLONED)), "key + repo set → team, clone not a prerequisite")
        assertEquals(FirstRunStep.TEAM, firstRunOpenStep(status(true, key = true, clone = CloneStatus.CLONED_OK)), "both core done → team (optional)")
    }

    @Test
    fun cloneInProgress_onlyWhileActivelyCloning() {
        // ★ B1 spin-fix: only CLONING keeps the §7.3 poll alive. CONFIGURED_NEVER_CLONED is STATIC under B1 (no source
        // moves it to terminal) → including it would spin the poll forever. Mutation: re-add CONFIGURED_NEVER_CLONED
        // to isCloneInProgress → its assertion below reddens.
        assertTrue(isCloneInProgress(CloneStatus.CLONING), "actively cloning → poll runs (B2 backend drives this)")
        assertTrue(!isCloneInProgress(CloneStatus.CONFIGURED_NEVER_CLONED), "never-cloned is static under B1 — not in progress, no poll spin")
        assertTrue(!isCloneInProgress(CloneStatus.NOT_CONFIGURED))
        assertTrue(!isCloneInProgress(CloneStatus.CLONED_OK))
        assertTrue(!isCloneInProgress(CloneStatus.CLONE_FAILED))
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
