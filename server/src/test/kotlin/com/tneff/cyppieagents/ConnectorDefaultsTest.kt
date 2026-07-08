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
 * ("the connector default NEVER bypasses") is deliberately inverted for the **local** spawn: with
 * `skipPermissions = true` the args carry `--dangerously-skip-permissions` so headless agents work without an
 * interactive approval. **Scoping (CYP-321 review):** the flag is on the `skipPermissions` PARAMETER (default
 * `false`), passed only by the local [ClaudeCodeConnector]; the SHARED default stays bypass-free so a direct
 * caller like the remote `BridgeMain` (user's own machine) never inherits it. These tests are inverted/
 * re-documented (NOT deleted): the local path (`skipPermissions=true`) carries the FLAG (never the
 * `bypassPermissions` MODE / no double `--permission-mode`); the shared default is bypass-free; the grant-gated
 * sandbox path (CYP-163) stays a distinct mechanism. Revert = flip [MVP_SKIP_PERMISSIONS].
 */
class ConnectorDefaultsTest {

    @Test
    fun localSpawn_carriesSkipFlag_viaTheFlagNotTheModeString() {
        // CYP-321: the LOCAL spawn (skipPermissions=true) bypasses — via the flag, not `--permission-mode`.
        val args = ConnectorDefaults.streamJsonArgs(skipPermissions = true)
        assertTrue(ConnectorDefaults.bypassesPermissions(args), "local spawn bypasses (CYP-321)")
        assertTrue(args.contains(ConnectorDefaults.DANGEROUS_FLAG), "the local spawn carries --dangerously-skip-permissions")
        // supersede, not double: the flag REPLACES --permission-mode (Context7-verified equivalence).
        assertFalse(args.contains("--permission-mode"), "no --permission-mode alongside the flag (no double directive)")
        assertFalse(args.contains("bypassPermissions"), "bypass is via the FLAG, never the forbidden MODE string")
    }

    @Test
    fun localSpawn_carriesSkipFlag_onFreshAndResumePaths() {
        assertTrue(ConnectorDefaults.streamJsonArgs(skipPermissions = true).contains(ConnectorDefaults.DANGEROUS_FLAG), "fresh local spawn carries the flag")
        val resumed = ConnectorDefaults.streamJsonArgs(resumeSessionId = "sess-1", skipPermissions = true)
        assertTrue(resumed.contains(ConnectorDefaults.DANGEROUS_FLAG), "resume local spawn also carries the flag")
        assertEquals("--resume", resumed.first(), "--resume still prepended (position preserved)")
        assertFalse(resumed.contains("--permission-mode"), "resume path also emits no --permission-mode")
    }

    @Test
    fun sharedDefault_isBypassFree_forDirectCallersLikeBridge() {
        // CYP-321 scoping: the SHARED default (skipPermissions=false) — what the remote BridgeMain calls on the
        // user's own machine — must NOT carry the flag; it stays on the tight --permission-mode path.
        val args = ConnectorDefaults.streamJsonArgs()
        assertFalse(ConnectorDefaults.bypassesPermissions(args), "the shared default is bypass-free (no leak to remote/BYOA)")
        assertFalse(args.contains(ConnectorDefaults.DANGEROUS_FLAG), "no --dangerously-skip-permissions on the shared default")
        assertTrue(args.contains("--permission-mode"), "the shared default keeps the tight permission mode")
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
    fun localSpawnAndSandboxOverride_bothBypass_viaDistinctMechanisms_guardStillSharp() {
        // CYP-321 re-point: BOTH bypass, but via DISTINCT mechanisms —
        //  - the sandbox override (CYP-163): grant-gated, via the `bypassPermissions` MODE;
        //  - the local spawn (CYP-321, skipPermissions=true): via the FLAG (--dangerously-skip-permissions).
        val sandbox = ConnectorDefaults.sandboxBypassStreamJsonArgs(SandboxBypassGrant.rb1Sandbox())
        assertTrue(ConnectorDefaults.bypassesPermissions(sandbox) && sandbox.contains("bypassPermissions"), "sandbox = MODE mechanism")
        val local = ConnectorDefaults.streamJsonArgs(skipPermissions = true)
        assertTrue(local.contains(ConnectorDefaults.DANGEROUS_FLAG) && !local.contains("bypassPermissions"), "local spawn = FLAG mechanism")
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
