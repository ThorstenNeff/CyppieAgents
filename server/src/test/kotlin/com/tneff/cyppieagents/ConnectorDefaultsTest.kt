package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorDefaults
import com.tneff.cyppieagents.connector.SandboxBypassGrant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reviewer Gate #4: bypassPermissions must never be the connector default. */
class ConnectorDefaultsTest {

    @Test
    fun defaultArgsNeverBypassPermissions() {
        val args = ConnectorDefaults.streamJsonArgs()
        assertFalse(ConnectorDefaults.bypassesPermissions(args))
        assertFalse(args.contains(ConnectorDefaults.DANGEROUS_FLAG))
        assertFalse(args.contains("bypassPermissions"))
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
    fun sandboxOverrideAndProdDefaultAreTwoDistinctPaths_nonLeak() {
        // Guard axis (b) "non-leak": two STRUCTURALLY DISJOINT paths — the override bypasses, the default
        // does NOT and fails closed on a bypass mode. (If they were one bent path, the leak mutation is moot.)
        assertTrue(ConnectorDefaults.bypassesPermissions(ConnectorDefaults.sandboxBypassStreamJsonArgs(SandboxBypassGrant.rb1Sandbox())))
        assertFalse(ConnectorDefaults.bypassesPermissions(ConnectorDefaults.streamJsonArgs()), "prod default never bypasses")
        // and the prod default path stays sharp — it cannot be coaxed into bypass even if asked (Gate #4).
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
