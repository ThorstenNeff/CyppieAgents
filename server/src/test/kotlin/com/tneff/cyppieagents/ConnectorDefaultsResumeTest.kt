package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.SandboxBypassGrant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-167 — `--resume` arg emission. The flag must appear iff a resume id is supplied, and BOTH arg
 * paths (prod [ConnectorDefaults.streamJsonArgs] and the sandbox-bypass override) must emit it
 * identically (one `withResume` single-source), without disturbing Gate #4.
 */
class ConnectorDefaultsResumeTest {

    /** Asserts `--resume <id>` appears exactly once, immediately followed by the id. */
    private fun assertResume(args: List<String>, id: String) {
        val i = args.indexOf("--resume")
        assertTrue(i >= 0, "args must contain --resume: $args")
        assertEquals(id, args.getOrNull(i + 1), "--resume must be immediately followed by the id")
        assertEquals(1, args.count { it == "--resume" }, "--resume must appear exactly once")
    }

    /** M1 — no resume id ⇒ NO `--resume` (first-start invariant). Mutation: always prepend → reds. */
    @Test
    fun m1_noResumeId_noFlag() {
        assertFalse(ConnectorDefaults.streamJsonArgs(resumeSessionId = null).contains("--resume"))
        assertFalse(ConnectorDefaults.streamJsonArgs(resumeSessionId = "").contains("--resume"), "blank id ⇒ no flag")
    }

    /** M2 — a resume id ⇒ `--resume <id>` prepended in the prod path. Mutation: ignore param → reds. */
    @Test
    fun m2_resumeId_prependsFlag_prodPath() {
        val args = ConnectorDefaults.streamJsonArgs(resumeSessionId = "sess-1")
        assertResume(args, "sess-1")
        assertEquals("--resume", args.first(), "--resume is prepended (verified flag position)")
        assertTrue(args.containsAll(ConnectorDefaults.BASE_STREAM_JSON_FLAGS), "base flags still present")
    }

    /**
     * M9 — the sandbox-bypass path emits `--resume` IDENTICALLY (both paths share `withResume`), and the
     * resume flag does NOT disturb the permission mechanism: the sandbox path still emits the
     * `bypassPermissions` MODE, and the MVP prod path (CYP-321) still emits the skip FLAG — resume or not.
     * Mutation: thread resume into only one path → reds.
     */
    @Test
    fun m9_bothArgPathsEmitResumeConsistently_gate4Intact() {
        val grant = SandboxBypassGrant.rb1Sandbox()
        val bypassWithResume = ConnectorDefaults.sandboxBypassStreamJsonArgs(grant, resumeSessionId = "sess-9")
        assertResume(bypassWithResume, "sess-9")
        assertTrue(ConnectorDefaults.bypassesPermissions(bypassWithResume), "bypass path still bypasses (Gate #4 unchanged)")

        val bypassNoResume = ConnectorDefaults.sandboxBypassStreamJsonArgs(grant, resumeSessionId = null)
        assertFalse(bypassNoResume.contains("--resume"), "no id ⇒ no flag, even on the bypass path")

        // CYP-321: the LOCAL spawn (skipPermissions=true) bypasses via the FLAG, and adding --resume does not
        // disturb that (resume single-source is orthogonal to the permission mechanism) — flag present, MODE absent.
        val localResumed = ConnectorDefaults.streamJsonArgs(resumeSessionId = "sess-9", skipPermissions = true)
        assertTrue(ConnectorDefaults.bypassesPermissions(localResumed), "local spawn bypasses via the flag, resume or not (CYP-321)")
        assertTrue(localResumed.contains(ConnectorDefaults.DANGEROUS_FLAG) && !localResumed.contains("bypassPermissions"))
    }
}
