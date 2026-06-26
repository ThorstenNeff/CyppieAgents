package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Platform-neutral ACL decision table for the default hub-and-spoke topology:
 * po is in po-frontend and po-backend; each worker only in its own spoke.
 */
class AclMatrixTest {

    private val channels = listOf(
        Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend")),
        Channel("po-backend", "po-backend", ChannelKind.HUB, listOf("po", "backend")),
    )
    private val entries = listOf(
        AclEntry("po-frontend", "po", canRead = true, canWrite = true),
        AclEntry("po-frontend", "frontend", canRead = true, canWrite = true),
        AclEntry("po-backend", "po", canRead = true, canWrite = true),
        AclEntry("po-backend", "backend", canRead = true, canWrite = true),
    )
    private val acl = AclMatrix(channels, entries)

    @Test
    fun poCanReadAndWriteBothSpokes() {
        assertTrue(acl.canWrite("po-frontend", "po"))
        assertTrue(acl.canWrite("po-backend", "po"))
        assertEquals(2, acl.readableChannels("po").size)
    }

    @Test
    fun workerOnlyInOwnSpoke() {
        assertTrue(acl.canWrite("po-backend", "backend"))
        // backend is NOT a member of po-frontend → fail-closed even without an explicit entry.
        assertFalse(acl.canWrite("po-frontend", "backend"))
        assertFalse(acl.canRead("po-frontend", "backend"))
        assertEquals(listOf("po-backend"), acl.readableChannels("backend").map { it.id })
    }

    @Test
    fun revokedCanWriteIsFailClosed() {
        val revoked = AclMatrix(
            channels,
            entries.map { if (it.channelId == "po-backend" && it.agentId == "backend") it.copy(canWrite = false) else it },
        )
        assertFalse(revoked.canWrite("po-backend", "backend"))
        // reading still allowed — write and read are independent
        assertTrue(revoked.canRead("po-backend", "backend"))
    }

    @Test
    fun unknownChannelOrAgentFailsClosed() {
        assertFalse(acl.canRead("does-not-exist", "po"))
        assertFalse(acl.canWrite("po-backend", "ghost"))
    }

    @Test
    fun visibleMessagesFiltersByReadability() {
        val msgs = listOf(
            Message("1", "po-frontend", "po", "a", 1),
            Message("2", "po-backend", "po", "b", 2),
        )
        // backend may only see po-backend messages.
        assertEquals(listOf("2"), acl.visibleMessages("backend", msgs).map { it.id })
    }
}
