package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.ForbiddenException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Project scoping at the Hub seam (S12 / CYP-81): the server single-sources the active project and
 * (1) stamps it on every posted message, (2) denies writes to a channel outside it, (3) never serves
 * a message from another project — all fail-closed, reusing the [com.tneff.cyppieagents.model.AclMatrix]
 * chokepoint.
 */
class HubProjectScopeTest {

    private val agents = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "Frontend", Role.WORKER, "frontend"),
    )

    @Test
    fun postedMessageIsStampedWithActiveProject() {
        val hub = Hub(HubState.hubAndSpoke(agents, operatorId = null, activeProjectId = "alpha"), InMemoryMessageStore())
        val msg = hub.postAsAgent("frontend", "po-frontend", "ready")
        assertEquals("alpha", msg.projectId)
        // and it is readable back in-project
        assertEquals(listOf("ready"), hub.channelMessages("frontend", "po-frontend").map { it.body })
    }

    @Test
    fun writeToChannelInAnotherProjectIsDenied() {
        // The channel and its grants live in project "beta", but the hub is active in "alpha". The
        // sender holds full canWrite in beta — scoping (not the ACL flag) must still deny.
        // Mutation: drop the AclMatrix construction filter → this post succeeds.
        val state = HubState(
            agents = agents,
            initialChannels = listOf(Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend"), projectId = "beta")),
            initialEntries = listOf(
                AclEntry("po-frontend", "po", canRead = true, canWrite = true, projectId = "beta"),
                AclEntry("po-frontend", "frontend", canRead = true, canWrite = true, projectId = "beta"),
            ),
            activeProjectId = "alpha",
        )
        val hub = Hub(state, InMemoryMessageStore())
        assertFailsWith<ForbiddenException> { hub.postAsAgent("frontend", "po-frontend", "leak") }
    }

    @Test
    fun outOfProjectMessageIsNotServed() {
        // Reader may read po-frontend (alpha), but a message tagged "beta" sitting in that channel id
        // must be filtered out on read. Mutation: drop the visibleMessages project clause → it leaks.
        val store = InMemoryMessageStore()
        store.append(Message("x", "po-frontend", "po", "from-beta", 1, projectId = "beta"))
        store.append(Message("y", "po-frontend", "po", "from-alpha", 2, projectId = "alpha"))
        val hub = Hub(HubState.hubAndSpoke(agents, operatorId = null, activeProjectId = "alpha"), store)
        val visible = hub.channelMessages("frontend", "po-frontend")
        assertEquals(listOf("y"), visible.map { it.id })
        assertTrue(visible.all { it.projectId == "alpha" })
    }
}
