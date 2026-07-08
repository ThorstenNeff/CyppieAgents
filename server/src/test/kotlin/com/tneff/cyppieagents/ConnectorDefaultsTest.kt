package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.SandboxBypassGrant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate #4 — RE-POINTED for the MVP (CYP-321, Auftraggeber-authorized 2026-07-08). The former invariant
 * ("the connector default NEVER bypasses") is deliberately inverted: the MVP default now carries
 * `--dangerously-skip-permissions` so headless agents work without an interactive approval. These tests are
 * inverted/re-documented (NOT deleted): they now positively assert the MVP default carries the FLAG, that it
 * does so via the flag (never the `bypassPermissions` MODE string / no double `--permission-mode`), and that
 * the grant-gated sandbox path (CYP-163) stays a distinct mechanism. Revert = flip [MVP_SKIP_PERMISSIONS].
 */
class ConnectorDefaultsTest {

    @Test
    fun mvpDefault_carriesSkipFlag_viaTheFlagNotTheModeString() {
        // CYP-321: the MVP default DOES bypass — via the flag (Auftraggeber-authorized), not `--permission-mode`.
        val args = ConnectorDefaults.streamJsonArgs()
        assertTrue(ConnectorDefaults.bypassesPermissions(args), "MVP default bypasses (CYP-321)")
        assertTrue(args.contains(ConnectorDefaults.DANGEROUS_FLAG), "the MVP default carries --dangerously-skip-permissions")
        // supersede, not double: the flag REPLACES --permission-mode (Context7-verified equivalence).
        assertFalse(args.contains("--permission-mode"), "no --permission-mode alongside the flag (no double directive)")
        assertFalse(args.contains("bypassPermissions"), "bypass is via the FLAG, never the forbidden MODE string")
    }

    @Test
    fun mvpDefault_carriesSkipFlag_onFreshAndResumePaths() {
        assertTrue(ConnectorDefaults.streamJsonArgs().contains(ConnectorDefaults.DANGEROUS_FLAG), "fresh spawn carries the flag")
        val resumed = ConnectorDefaults.streamJsonArgs(resumeSessionId = "sess-1")
        assertTrue(resumed.contains(ConnectorDefaults.DANGEROUS_FLAG), "resume spawn also carries the flag")
        assertEquals("--resume", resumed.first(), "--resume still prepended (position preserved)")
        assertFalse(resumed.contains("--permission-mode"), "resume path also emits no --permission-mode")
    }

    @Test
    fun defaultArgsCarryVerifiedStreamJsonFlags() {
        val args = ConnectorDefaults.streamJsonArgs()
        assertTrue(args.containsAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "--verbose")))
        // MVP: partial messages OFF.
        assertFalse(args.contains("--include-partial-messages"))
    }

    @Test
    fun bypassPermissionModeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConnectorDefaults.streamJsonArgs(permissionMode = "bypassPermissions")
        }
    }

    // ---- CYP-163: the sandbox-only bypass override (human + reviewer signed) ----

    @Test
    fun sandboxOverrideEmitsBypass_withAnExplicitGrant() {
        // Guard axis (a) "write-enabled": the grant-gated override DOES emit bypassPermissions, visible in args.
        val args = ConnectorDefaults.sandboxBypassStreamJsonArgs(SandboxBypassGrant.rb1Sandbox())
        assertTrue(ConnectorDefaults.bypassesPermissions(args), "the sandbox override must enable bypass for the worker")
        assertTrue(args.contains("bypassPermissions"), "the bypass flag is inspectable in the spawn args")
    }

    @Test
    fun mvpDefaultAndSandboxOverride_bothBypass_viaDistinctMechanisms_guardStillSharp() {
        // CYP-321 re-point: in the MVP BOTH paths bypass, but via DISTINCT mechanisms —
        //  - the sandbox override (CYP-163): grant-gated, via the `bypassPermissions` MODE;
        //  - the MVP default (CYP-321): via the FLAG (--dangerously-skip-permissions).
        val sandbox = ConnectorDefaults.sandboxBypassStreamJsonArgs(SandboxBypassGrant.rb1Sandbox())
        assertTrue(ConnectorDefaults.bypassesPermissions(sandbox) && sandbox.contains("bypassPermissions"), "sandbox = MODE mechanism")
        val mvp = ConnectorDefaults.streamJsonArgs()
        assertTrue(mvp.contains(ConnectorDefaults.DANGEROUS_FLAG) && !mvp.contains("bypassPermissions"), "MVP default = FLAG mechanism")
        // Gate #4 stays sharp on the MODE vector: the forbidden `bypassPermissions` MODE is still rejected
        // (the sanctioned MVP bypass is the flag, never the mode string re-appearing via the param).
        assertFailsWith<IllegalArgumentException> {
            ConnectorDefaults.streamJsonArgs(permissionMode = ConnectorDefaults.FORBIDDEN_PERMISSION_MODE)
        }
    }

    @Test
    fun cliVersionIsPinned() {
        // CYP-160: pinned to the installed 2.1.196 (host moved on; stream-json surface identical; CYP-59/61).
        assertEquals("2.1.196", ConnectorDefaults.PINNED_CLI_VERSION)
    }
}
