package com.tneff.cyppieagents.agentview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * CYP-738 — the agent composer send-writability derivation (the §4a-safe tri-state + the dormant/active split).
 * Pins: a not-writable agent → READ_ONLY, an undetermined write-right (fetch failure) → UNKNOWN (disabled), NEVER
 * editable; no seam → UNGATED (dormant), structurally distinct from UNKNOWN. Mutations that redden here:
 * not-in-set→WRITABLE, and failure→WRITABLE or →UNGATED (the leak the PL guardrail forbids).
 */
class Cyp738ComposerWritabilityTest {

    @Test
    fun noSeam_isUngated_theDormantEditablePath() {
        // No AgentWritableApi injected → the gate is dormant (byte-identical unconditional-editable). This is the
        // ONLY editable-without-a-signal path — deliberately distinct from UNKNOWN.
        assertEquals(AgentComposerWritability.UNGATED, deriveComposerWritability(null, "frontend"))
    }

    @Test
    fun agentInWritableSet_isWritable() {
        assertEquals(
            AgentComposerWritability.WRITABLE,
            deriveComposerWritability(Result.success(listOf("po", "frontend")), "frontend"),
        )
    }

    @Test
    fun agentNotInWritableSet_isReadOnly_notEditable() {
        // Proactive read-only: the agent is not in the writable set → READ_ONLY, never editable.
        // Mutation (not-in-set → WRITABLE) reddens here.
        assertEquals(
            AgentComposerWritability.READ_ONLY,
            deriveComposerWritability(Result.success(listOf("po", "backend")), "frontend"),
        )
    }

    @Test
    fun fetchFailure_isUnknown_disabled_neverEditable_plGuardrail() {
        // The §4a guardrail: an undetermined write-right (endpoint error / pre-deploy) is UNKNOWN (disabled),
        // UNCONDITIONALLY — never WRITABLE and never the dormant UNGATED editable path. Mutation (failure →
        // WRITABLE or → UNGATED) reddens here.
        val unknown = deriveComposerWritability(Result.failure(RuntimeException("endpoint down")), "frontend")
        assertEquals(AgentComposerWritability.UNKNOWN, unknown, "a wired runtime-unknown must be UNKNOWN (disabled)")
        assertNotEquals(AgentComposerWritability.WRITABLE, unknown, "UNKNOWN must never be editable")
        assertNotEquals(AgentComposerWritability.UNGATED, unknown, "UNKNOWN must not reuse the dormant editable path")
    }
}
