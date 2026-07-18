package com.tneff.cyppieagents.firstrun

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-629 Inc4 — the pure [workspaceUnconfigured] derivation for the degraded-workspace banner (ux-spec §6.2/§6.3a).
 * Visible ⇔ the gate would be ACTIVE; SPECIFIC (names what's open via the reused step labels); and the one edge a
 * generic message would lie about — a set-but-`CLONE_FAILED` repo is NOT "missing", it carries the clone-error copy.
 */
class Cyp629WorkspaceUnconfiguredModelTest {

    private fun st(loaded: Boolean = true, apiKeySet: Boolean, clone: CloneStatus, reason: CloneFailReason? = null) =
        FirstRunConfigStatus(loaded, apiKeySet, clone, reason)

    @Test
    fun done_isInvisible() {
        // TRANSPARENT (key set AND CLONED_OK) → the banner clears itself, no lingering nag.
        val s = workspaceUnconfigured(st(apiKeySet = true, clone = CloneStatus.CLONED_OK))
        assertFalse(s.visible)
        assertFalse(s.missingApiKey); assertFalse(s.missingRepo); assertFalse(s.cloneFailed)
    }

    @Test
    fun unknown_isInvisible_notAsserted() {
        // LOADING (not loaded) → we do NOT assert "unconfigured" while unsure (fail-closed on unknown).
        val s = workspaceUnconfigured(FirstRunConfigStatus.Unknown)
        assertFalse(s.visible)
    }

    @Test
    fun bothMissing_namesBoth() {
        val s = workspaceUnconfigured(st(apiKeySet = false, clone = CloneStatus.NOT_CONFIGURED))
        assertTrue(s.visible)
        assertTrue(s.missingApiKey)
        assertTrue(s.missingRepo)
        assertFalse(s.cloneFailed)
    }

    @Test
    fun keySet_repoSet_isConfigured_notDegraded() {
        // B1: key set AND repo set (never cloned) = CONFIGURED (transparent) → NOT a degraded workspace. The clone is
        // best-effort and never a prerequisite; the banner does not treat a set-but-uncloned repo as "missing".
        val s = workspaceUnconfigured(st(apiKeySet = true, clone = CloneStatus.CONFIGURED_NEVER_CLONED))
        assertFalse(s.visible, "a configured hub (key + repo set) shows no unconfigured banner")
        assertFalse(s.missingRepo)
    }

    @Test
    fun repoCloned_keyMissing_namesApiKeyOnly() {
        val s = workspaceUnconfigured(st(apiKeySet = false, clone = CloneStatus.CLONED_OK))
        assertTrue(s.visible)
        assertTrue(s.missingApiKey)
        assertFalse(s.missingRepo)
    }

    @Test
    fun cloneFailed_isNotMissingRepo_carriesReason() {
        // ★ SHARP (the CYP-639 confusion §7 closes): a set-but-failed repo is NOT "repository missing" — it reads the
        // clone-error copy. The key is missing here (so the hub is ACTIVE/degraded), the repo is set-but-failed.
        // Mutation: change missingRepo to `cloneStatus != CLONED_OK` → CLONE_FAILED != CLONED_OK → missingRepo true → red.
        // (B1: CLONE_FAILED is dormant — no source produces it — but the logic is pinned honest for CYP-684/B2.)
        val s = workspaceUnconfigured(st(apiKeySet = false, clone = CloneStatus.CLONE_FAILED, reason = CloneFailReason.AUTH))
        assertTrue(s.visible)
        assertTrue(s.missingApiKey, "the key is what's open here")
        assertTrue(s.cloneFailed)
        assertFalse(s.missingRepo, "a set-but-failed repo is NOT 'missing' — it carries the clone-error copy")
        assertEquals(CloneFailReason.AUTH, s.cloneReason)
    }

    @Test
    fun keySet_repoCloning_isConfigured_notDegraded() {
        // B1: a set repo that is (best-effort) cloning is still CONFIGURED → no degraded banner. "Cloning" is not
        // "missing"; the gate is transparent and the workspace is open.
        val s = workspaceUnconfigured(st(apiKeySet = true, clone = CloneStatus.CLONING))
        assertFalse(s.visible)
        assertFalse(s.missingRepo)
        assertFalse(s.cloneFailed)
        assertNull(s.cloneReason)
    }
}
