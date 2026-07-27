package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.commRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reviewer merge-gate (S12 / CYP-81): `GET /api/acl` is an ACL-metadata egress and must be project-
 * scoped like the rest of the decision surface — it must NOT return raw cross-project entries. Active
 * project "alpha" with an injected "beta" entry; the beta entry must be absent from the response.
 * Mutation: revert the route to `state.entries` (operator) / raw `state.channels` (agent) → red.
 */
class AclEgressProjectScopeTest {

    private val op = HubState.OPERATOR_ID

    private fun state(): HubState {
        val agents = listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend"))
        val channels = listOf(
            Channel("po-frontend", "po-frontend", ChannelKind.HUB, listOf("po", "frontend", op), projectId = "alpha"),
            // frontend is ALSO a (raw) member of this beta channel — so the agent branch is non-vacuum:
            // the unscoped code would leak its beta entry to frontend; the scoped code must not.
            Channel("beta-secret", "beta-secret", ChannelKind.HUB, listOf("po", "frontend", op), projectId = "beta"),
        )
        val entries = listOf(
            AclEntry("po-frontend", "po", canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("po-frontend", "frontend", canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("po-frontend", op, canRead = true, canWrite = true, projectId = "alpha"),
            // Out-of-project (beta) entries — must NOT egress while the active project is alpha. The
            // frontend beta entry is what makes the AGENT branch bite (frontend is a beta member).
            AclEntry("beta-secret", "po", canRead = true, canWrite = true, projectId = "beta"),
            AclEntry("beta-secret", "frontend", canRead = true, canWrite = true, projectId = "beta"),
            AclEntry("beta-secret", op, canRead = true, canWrite = true, projectId = "beta"),
        )
        return HubState(agents, channels, entries, activeProjectId = "alpha")
    }

    @Test
    fun getAcl_isProjectScoped_noCrossProjectLeak() = testApplication {
        val state = state()
        val registry = TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op", loopbackPosture = true)
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(WebSockets)
            routing { commRoutes(Hub(state, InMemoryMessageStore()), state, registry) }
        }
        val client = createClient { install(ClientContentNegotiation) { json(CommJson) } }

        // Operator: only the in-project (alpha) entries; the beta-secret entries are gone.
        val opEntries: List<AclEntry> = client.get("/api/acl") { bearerAuth("tok-op") }.body()
        assertEquals(3, opEntries.size)
        assertTrue(opEntries.all { it.projectId == "alpha" }, "operator GET /acl is project-scoped")
        assertFalse(opEntries.any { it.channelId == "beta-secret" }, "no cross-project ACL entry leaks")

        // Agent branch routed through the same scoped matrix: frontend is a (raw) member of the beta
        // channel, but its beta entry must NOT egress — only its in-project (alpha) entries do.
        // Mutation: revert the agent branch to raw state.entries + raw membership → beta-secret leaks → red.
        val feEntries: List<AclEntry> = client.get("/api/acl") { bearerAuth("tok-fe") }.body()
        assertTrue(feEntries.isNotEmpty())
        assertFalse(feEntries.any { it.channelId == "beta-secret" }, "no cross-project entry leaks to the agent")
        assertTrue(feEntries.all { it.channelId == "po-frontend" && it.projectId == "alpha" })
    }
}
