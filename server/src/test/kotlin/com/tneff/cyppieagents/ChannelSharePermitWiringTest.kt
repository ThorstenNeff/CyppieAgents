package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S17 / CYP-93 — the HubState wiring of the permit: the share store feeds `sharedInboundProvider`, and
 * `refreshShares()` recomputes + rebuilds the matrix so a set/revoke takes effect WITHOUT a restart.
 * Proves the permit takes effect on share and that revoke is the gate (immediate fail-closed) even with
 * the grantee's AclEntry still present.
 */
class ChannelSharePermitWiringTest {

    private val channels = listOf(
        Channel("c", "c", ChannelKind.GROUP, members = listOf("a1", "b1"), projectId = "alpha"),
    )
    private val entries = listOf(
        AclEntry("c", "a1", canRead = true, canWrite = true, projectId = "alpha"),
        AclEntry("c", "b1", canRead = true, canWrite = false, projectId = "beta"), // grantee, beta-stamped
    )

    private fun wired(shares: ChannelShareStore): HubState =
        HubState(emptyList(), channels, entries, activeProjectId = "beta", operatorId = null) { pid ->
            shares.sharedInboundChannelIds(pid)
        }

    @Test fun shareTakesEffect_thenRevokeFailsClosed() {
        val shares = ChannelShareStore(null)
        val state = wired(shares)

        // before any share: c (alpha) is out of beta's scope → grantee can't read it
        assertFalse(state.acl.canRead("c", "b1"), "fail-closed before share")

        // owner shares c → beta; refresh makes the permit live
        shares.share("c", ownerProjectId = "alpha", sharedWith = setOf("beta"))
        state.refreshShares()
        assertTrue(state.acl.canRead("c", "b1"), "after share + refresh, the grantee reads the shared channel")
        assertTrue(state.acl.readableChannels("b1").any { it.id == "c" })

        // revoke: the gate closes — c falls back to fail-closed even though b1's entry still exists
        shares.revoke("c")
        state.refreshShares()
        assertFalse(state.acl.canRead("c", "b1"), "revoke → immediate fail-closed (gate, not entries)")
    }
}
