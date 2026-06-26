package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP-level enforcement tests for the comm hub (Slice S4 AC + Reviewer Gates #1/#2).
 * Default hub-and-spoke topology: po in po-frontend & po-backend; each worker only in its own.
 */
class CommHubAclTest {

    private fun config(store: MessageStore = InMemoryMessageStore()) = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = store,
    )

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun postToOwnSpokeThenRead() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()

        val post = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("ready"))
        }
        assertEquals(HttpStatusCode.Created, post.status)
        val created: Message = post.body()
        assertEquals("backend", created.from)
        assertEquals("po-backend", created.channelId)

        val read = client.get("/api/channels/po-backend/messages") { bearerAuth("tok-po") }
        assertEquals(HttpStatusCode.OK, read.status)
        val msgs: List<Message> = read.body()
        assertEquals(listOf("ready"), msgs.map { it.body })
    }

    @Test
    fun postToForeignChannelIsForbidden() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // backend is NOT a member of po-frontend → canWrite false → 403 (Gate #2 fail-closed).
        val res = client.post("/api/channels/po-frontend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("intrusion"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun readForeignChannelIsForbidden() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val res = client.get("/api/channels/po-frontend/messages") { bearerAuth("tok-backend") }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun defaultTopologyIsHubAndSpoke() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()

        val backendChannels: List<Channel> =
            client.get("/api/channels") { bearerAuth("tok-backend") }.body()
        assertEquals(listOf("po-backend"), backendChannels.map { it.id })

        val poChannels: List<Channel> =
            client.get("/api/channels") { bearerAuth("tok-po") }.body()
        assertEquals(setOf("po-frontend", "po-backend"), poChannels.map { it.id }.toSet())
    }

    @Test
    fun inboxIsAclFiltered() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // po writes into po-frontend; backend must NOT see it, frontend must.
        client.post("/api/channels/po-frontend/messages") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("for frontend"))
        }
        val backendInbox: List<Message> = client.get("/api/inbox") { bearerAuth("tok-backend") }.body()
        assertTrue(backendInbox.isEmpty())
        val frontendInbox: List<Message> = client.get("/api/inbox") { bearerAuth("tok-frontend") }.body()
        assertEquals(listOf("for frontend"), frontendInbox.map { it.body })
    }

    @Test
    fun noTokenIsUnauthorized() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/channels").status)
    }

    @Test
    fun operatorCanRevokeCanWriteAndPostThenFails() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // Operator revokes backend's write on its own spoke.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "backend", canRead = true, canWrite = false))
        }
        assertEquals(HttpStatusCode.OK, put.status)
        // Now the same post that worked before is refused.
        val res = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("blocked now"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun nonOperatorCannotChangeAcl() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val res = client.put("/api/acl") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "backend", canRead = true, canWrite = true))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
    }

    @Test
    fun agentsListCarriesNoTokens() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        val raw = client.get("/api/agents") { bearerAuth("tok-po") }
        assertEquals(HttpStatusCode.OK, raw.status)
        val text: String = raw.body()
        // security-by-structure: tokens are not even a field, so they cannot leak.
        assertFalse(text.contains("tok-"))
        assertFalse(text.contains("token"))
    }
}
