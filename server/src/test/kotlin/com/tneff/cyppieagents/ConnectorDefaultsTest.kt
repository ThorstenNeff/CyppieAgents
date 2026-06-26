package com.tneff.cyppieagents

import com.tneff.cyppieagents.connector.ConnectorDefaults
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

    @Test
    fun cliVersionIsPinned() {
        assertEquals("2.1.193", ConnectorDefaults.PINNED_CLI_VERSION)
    }
}
