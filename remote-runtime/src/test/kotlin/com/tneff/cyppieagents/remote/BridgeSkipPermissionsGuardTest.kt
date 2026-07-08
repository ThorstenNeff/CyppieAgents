package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.ConnectorDefaults
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-321 (security scoping, Axis-2 guard) — the Remote Bridge spawns the USER's own Claude Code on the USER's
 * machine, so it MUST stay bypass-free. The MVP `--dangerously-skip-permissions` bypass is Auftraggeber-authorized
 * for the LOCAL connector's spawns only, NEVER a foreign user machine (CYP-197/BYOA). This pins that
 * [bridgeCliCommand] does NOT carry the flag — the missing tooth the security review flagged.
 *
 * Mutation: bake the flag back into the shared `ConnectorDefaults.streamJsonArgs` default (or have BridgeMain
 * pass `skipPermissions=true`) → the bridge inherits the bypass → this test reddens.
 */
class BridgeSkipPermissionsGuardTest {

    @Test
    fun bridgeCommand_isBypassFree_neverCarriesTheSkipFlagOrMode() {
        val cmd = bridgeCliCommand("claude")
        assertFalse(cmd.contains(ConnectorDefaults.DANGEROUS_FLAG),
            "the bridge (user's machine) must NEVER carry --dangerously-skip-permissions (CYP-321 scoping)")
        assertFalse(ConnectorDefaults.bypassesPermissions(cmd), "the bridge spawn is bypass-free")
        assertFalse(cmd.contains("bypassPermissions"), "nor the grant-only MODE string")
        // sanity: it IS the real stream-json spawn (so the absence above is meaningful, not an empty command).
        assertTrue(cmd.containsAll(listOf("claude", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose")),
            "the bridge still spawns the pinned stream-json session")
    }
}
