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
    fun keySet_repoNeverCloned_namesRepoOnly() {
        val s = workspaceUnconfigured(st(apiKeySet = true, clone = CloneStatus.CONFIGURED_NEVER_CLONED))
        assertTrue(s.visible)
        assertFalse(s.missingApiKey)
        assertTrue(s.missingRepo)
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
        // clone-error copy. Mutation: drop `&& !cloneFailed` from missingRepo → this flips missingRepo true → red.
        val s = workspaceUnconfigured(st(apiKeySet = true, clone = CloneStatus.CLONE_FAILED, reason = CloneFailReason.AUTH))
        assertTrue(s.visible)
        assertTrue(s.cloneFailed)
        assertFalse(s.missingRepo, "a set-but-failed repo is NOT 'missing' — it carries the clone-error copy")
        assertEquals(CloneFailReason.AUTH, s.cloneReason)
    }

    @Test
    fun cloning_isRepoNotReady_notFailed() {
        val s = workspaceUnconfigured(st(apiKeySet = true, clone = CloneStatus.CLONING))
        assertTrue(s.visible)
        assertTrue(s.missingRepo)
        assertFalse(s.cloneFailed)
        assertNull(s.cloneReason)
    }
}
