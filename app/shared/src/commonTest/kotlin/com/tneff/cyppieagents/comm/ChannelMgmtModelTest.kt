package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelMemberGrant
import com.tneff.cyppieagents.model.CreateChannelRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-883 (OS-C, Compose mirror of web-ts CYP-875 `channelMgmtModel.test.ts`) — the pure honesty model: client HINTS,
 * server stays authoritative. HUB is protected (not creatable, not archivable); a failed mutation maps to its honest
 * [ChannelMutationReason] from the server status.
 */
class ChannelMgmtModelTest {

    private fun ch(kind: ChannelKind) = Channel(id = "c", name = "C", kind = kind, members = emptyList())

    @Test
    fun creatableKinds_areDirectAndGroup_neverHub() {
        assertEquals(listOf(ChannelKind.DIRECT, ChannelKind.GROUP), CREATABLE_KINDS)
        assertFalse(ChannelKind.HUB in CREATABLE_KINDS)
    }

    @Test
    fun isArchivable_hubIsProtected_directAndGroupArchivable() {
        // MUT: return true for HUB → the UI would offer to archive a protected hub-and-spoke channel → reds.
        assertFalse(isArchivable(ch(ChannelKind.HUB)))
        assertTrue(isArchivable(ch(ChannelKind.DIRECT)))
        assertTrue(isArchivable(ch(ChannelKind.GROUP)))
    }

    @Test
    fun memberGrant_membershipIsTheAcl_readAndWrite() {
        assertEquals(ChannelMemberGrant("frontend", canRead = true, canWrite = true), memberGrant("frontend"))
    }

    @Test
    fun isCreateValid_needsIdNameCreatableKindAndAtLeastOneMember_failClosed() {
        val ok = CreateChannelRequest(id = "x", name = "X", kind = ChannelKind.GROUP, members = listOf(memberGrant("a")))
        assertTrue(isCreateValid(ok))
        assertFalse(isCreateValid(ok.copy(id = "   "))) // blank id
        assertFalse(isCreateValid(ok.copy(name = ""))) // blank name
        assertFalse(isCreateValid(ok.copy(members = emptyList()))) // no members
        assertFalse(isCreateValid(ok.copy(kind = ChannelKind.HUB))) // HUB is not a creatable kind
    }

    @Test
    fun channelMutationReason_mapsServerStatusHonestly() {
        // MUT: collapse 409/403 into GENERIC (hide the real cause) → reds.
        assertEquals(ChannelMutationReason.PROTECTED_HUB, channelMutationReason(CommHttpException(409, "")))
        assertEquals(ChannelMutationReason.OPERATOR_ONLY, channelMutationReason(CommHttpException(403, "")))
        assertEquals(ChannelMutationReason.GENERIC, channelMutationReason(CommHttpException(500, "")))
        assertEquals(ChannelMutationReason.GENERIC, channelMutationReason(RuntimeException("boom")))
    }
}
