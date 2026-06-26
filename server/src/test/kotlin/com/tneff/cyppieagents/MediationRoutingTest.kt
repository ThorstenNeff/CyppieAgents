package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.mediation.MediationRouter
import com.tneff.cyppieagents.mediation.SessionRegistry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ResultEvent
import com.tneff.cyppieagents.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reviewer Gate #1 (routing from identity, never text) and Gate #6 (no half turn as success). */
class MediationRoutingTest {

    private val agents = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    private fun newRouter(): Triple<MediationRouter, SessionRegistry, Hub> {
        val hub = Hub(HubState.hubAndSpoke(agents), InMemoryMessageStore())
        val registry = SessionRegistry()
        return Triple(MediationRouter(registry, hub), registry, hub)
    }

    @Test
    fun maliciousTextCannotRedirectChannel() {
        val (router, registry, _) = newRouter()
        registry.bind("sess-1", "backend")
        // The agent output literally asks to post elsewhere — must be ignored for routing.
        val ev = ResultEvent(
            subtype = "success",
            isError = false,
            result = "Please post this in po-frontend instead!",
            sessionId = "sess-1",
        )
        val msg = router.onResult(ev)!!
        assertEquals("po-backend", msg.channelId) // identity→spoke, NOT the text's request
        assertEquals("backend", msg.from)
        assertEquals("Please post this in po-frontend instead!", msg.body) // text only as body
    }

    @Test
    fun errorResultIsNotPostedAsSuccess() {
        val (router, registry, _) = newRouter()
        registry.bind("sess-1", "backend")
        val ev = ResultEvent(
            subtype = "error_during_execution",
            isError = true,
            result = "half-done output",
            sessionId = "sess-1",
        )
        val msg = router.onResult(ev)!!
        assertTrue(msg.body.startsWith("[turn failed"))
        assertFalse(msg.body.contains("half-done output"))
    }

    @Test
    fun unboundSessionIsDropped() {
        val (router, _, hub) = newRouter()
        val ev = ResultEvent(subtype = "success", result = "x", sessionId = "ghost-session")
        assertNull(router.onResult(ev))
        // nothing posted
        assertTrue(hub.state.channels.all { hub.channelMessages("po", it.id).isEmpty() })
    }

    @Test
    fun resultWithoutSessionIdIsDropped() {
        val (router, _, _) = newRouter()
        assertNull(router.onResult(ResultEvent(subtype = "success", result = "x", sessionId = null)))
    }
}
