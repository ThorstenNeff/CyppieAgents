package com.tneff.cyppieagents

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclMatrix
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.DEFAULT_PROJECT_ID
import com.tneff.cyppieagents.model.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Project scoping folded into the [AclMatrix] chokepoint (S12 / CYP-81): channels, ACL entries and
 * messages from another (or blank) project are out of the decision surface → DENY, composed with the
 * ACL (deny-wins). MVP=1 leaves same-project behavior unchanged ([AclMatrixTest] still green).
 */
class AclMatrixProjectScopeTest {

    private fun channel(id: String, project: String) =
        Channel(id, id, ChannelKind.HUB, listOf("po", "frontend"), projectId = project)

    private fun rw(channelId: String, agentId: String, project: String) =
        AclEntry(channelId, agentId, canRead = true, canWrite = true, projectId = project)

    @Test
    fun channelInAnotherProjectIsDenied() {
        // Same ids, same full read+write grants, but the channel/entries live in project "beta" while
        // the matrix is active in "alpha". Scoping (not the ACL flags) must deny.
        // Mutation: drop the construction filter → these flip to true.
        val acl = AclMatrix(
            channels = listOf(channel("po-frontend", "beta")),
            entries = listOf(rw("po-frontend", "po", "beta"), rw("po-frontend", "frontend", "beta")),
            activeProjectId = "alpha",
        )
        assertFalse(acl.isMember("po-frontend", "po"))
        assertFalse(acl.canRead("po-frontend", "frontend"))
        assertFalse(acl.canWrite("po-frontend", "frontend"))
        assertTrue(acl.readableChannels("frontend").isEmpty())
    }

    @Test
    fun sameProjectStillGrants() {
        val acl = AclMatrix(
            channels = listOf(channel("po-frontend", "alpha")),
            entries = listOf(rw("po-frontend", "frontend", "alpha")),
            activeProjectId = "alpha",
        )
        assertTrue(acl.canRead("po-frontend", "frontend"))
        assertTrue(acl.canWrite("po-frontend", "frontend"))
    }

    @Test
    fun crossProjectIsolation_twoProjectsSameChannelId() {
        // Two projects each with a "po-frontend" channel; the active matrix for "alpha" must only see
        // alpha's, proving ids do not leak across the tenant boundary.
        val acl = AclMatrix(
            channels = listOf(channel("po-frontend", "alpha"), channel("po-frontend", "beta")),
            entries = listOf(rw("po-frontend", "frontend", "alpha"), rw("po-frontend", "frontend", "beta")),
            activeProjectId = "alpha",
        )
        assertEquals(listOf("po-frontend"), acl.readableChannels("frontend").map { it.id })
        assertEquals(listOf("alpha"), acl.readableChannels("frontend").map { it.projectId })
    }

    @Test
    fun blankProjectIdChannelIsDenied() {
        // A channel with no project (blank) must NOT be treated as global/open — fail-closed.
        val acl = AclMatrix(
            channels = listOf(channel("po-frontend", "")),
            entries = listOf(rw("po-frontend", "frontend", "")),
            activeProjectId = "alpha",
        )
        assertFalse(acl.canRead("po-frontend", "frontend"))
    }

    @Test
    fun visibleMessagesDropOutOfProjectRows() {
        // Same channel the reader CAN read, but a message tagged with another/blank project must be
        // filtered out. Mutation: drop the ProjectScope clause in visibleMessages → "2"/"3" leak in.
        val acl = AclMatrix(
            channels = listOf(channel("po-frontend", "alpha")),
            entries = listOf(rw("po-frontend", "frontend", "alpha")),
            activeProjectId = "alpha",
        )
        val msgs = listOf(
            Message("1", "po-frontend", "po", "in", 1, projectId = "alpha"),
            Message("2", "po-frontend", "po", "other-project", 2, projectId = "beta"),
            Message("3", "po-frontend", "po", "blank-project", 3, projectId = ""),
        )
        assertEquals(listOf("1"), acl.visibleMessages("frontend", msgs).map { it.id })
    }

    @Test
    fun defaultedConstructionsRunInOneProject_noDrift() {
        // No projectId anywhere → everything defaults to DEFAULT_PROJECT_ID and the matrix is active
        // there → identical to pre-S12 behavior (this is what keeps the existing suites green).
        val acl = AclMatrix(
            channels = listOf(Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend"))),
            entries = listOf(AclEntry("po-frontend", "frontend", canRead = true, canWrite = true)),
        )
        assertEquals(DEFAULT_PROJECT_ID, acl.activeProjectId)
        assertTrue(acl.canWrite("po-frontend", "frontend"))
    }
}
