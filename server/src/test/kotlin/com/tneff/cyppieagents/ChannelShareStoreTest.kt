package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.ChannelShareStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S17 / CYP-93 — the cross-project share GATE ([ChannelShareStore]): directed set/revoke keyed per
 * channelId, the per-active-project inbound resolution the AclMatrix permit consults, fail-closed on a
 * blank active, and atomic 0600 persistence.
 */
class ChannelShareStoreTest {

    private fun store(file: File? = null) = ChannelShareStore(file, clock = { 1234L })

    @Test fun share_thenInboundForGrantee() {
        val s = store()
        s.share("c", ownerProjectId = "alpha", sharedWith = setOf("beta"))
        assertEquals(setOf("c"), s.sharedInboundChannelIds("beta"), "c reaches into beta")
        assertEquals(emptySet(), s.sharedInboundChannelIds("gamma"), "not into a non-grantee project")
        assertEquals(emptySet(), s.sharedInboundChannelIds("alpha"), "not into its own owner project")
    }

    @Test fun sharePerChannel_siblingNotIncluded() {
        val s = store()
        s.share("c", "alpha", setOf("beta")) // only c is shared, not c2
        assertEquals(setOf("c"), s.sharedInboundChannelIds("beta"), "only the shared channel id reaches beta")
    }

    @Test fun revoke_isTheGate() {
        val s = store()
        s.share("c", "alpha", setOf("beta"))
        assertTrue(s.revoke("c"))
        assertEquals(emptySet(), s.sharedInboundChannelIds("beta"), "revoke → no inbound reach")
        assertFalse(s.revoke("c"), "idempotent: already revoked")
    }

    @Test fun shareToOnlyOwnerOrEmpty_isNoOp() {
        val s = store()
        s.share("c", "alpha", setOf("alpha")) // sharing to your own project is not a cross-project hole
        assertEquals(emptySet(), s.sharedInboundChannelIds("alpha"))
        assertFalse(s.record("c") != null, "no record persisted for an empty/own-only share")
    }

    @Test fun blankActive_reachesNothing_failClosed() {
        val s = store()
        s.share("c", "alpha", setOf("beta", ""))
        assertEquals(emptySet(), s.sharedInboundChannelIds(""), "a blank active project reaches nothing")
    }

    @Test fun persists_0600_andReloads() {
        val dir = Files.createTempDirectory("share-store").toFile()
        try {
            val f = File(dir, "channel-shares.json")
            store(f).share("c", "alpha", setOf("beta"))
            assertEquals(setOf("c"), store(f).sharedInboundChannelIds("beta"), "reloads across restart")
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(f.toPath()), "0600",
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
