package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.comm.MessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.DeliveredMessage
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
        val created: Message = post.body<DeliveredMessage>().message // CYP-744: unwrap the DeliveredMessage envelope
        assertEquals("backend", created.from)
        assertEquals("po-backend", created.channelId)

        val read = client.get("/api/channels/po-backend/messages") { bearerAuth("tok-po") }
        assertEquals(HttpStatusCode.OK, read.status)
        val msgs: List<Message> = read.body<List<DeliveredMessage>>().map { it.message } // CYP-744: unwrap
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
        assertEquals(setOf("po-frontend", "po-backend", "op-po"), poChannels.map { it.id }.toSet()) // CYP-787: PO is a member of its op-po spoke
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

    // ----- CYP-49: server-side PO-lockout guardrail on PUT /api/acl -----
    // The PO is the hub of hub-and-spoke; the server (source of truth) rejects any operator change
    // that strips the PO's read OR write on a spoke it hubs, fail-closed (409), persisting nothing.
    // Mutation-proof note: each test below goes RED if the guard in HubState.setAcl is removed.

    @Test
    fun poLockoutViaCanWriteIsRejectedAndNotPersisted() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // Operator tries to revoke the PO's WRITE on a channel it is the hub of.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "po", canRead = true, canWrite = false))
        }
        assertEquals(HttpStatusCode.Conflict, put.status)
        // Contract with UIUX/Dev: stable machine-readable code, not just a message.
        val err: ApiErrorBody = put.body()
        assertEquals("po_lockout_protected", err.error.code)
        // Fail-closed: nothing persisted → the PO can still post into po-backend.
        val post = client.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("still the hub"))
        }
        assertEquals(HttpStatusCode.Created, post.status)
    }

    @Test
    fun poLockoutViaCanReadIsRejected() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // Adjacent vector: revoke the PO's READ. A canWrite-only guard would miss this.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-frontend", "po", canRead = false, canWrite = true))
        }
        assertEquals(HttpStatusCode.Conflict, put.status)
        // Fail-closed: the PO still reads po-frontend.
        val read = client.get("/api/channels/po-frontend/messages") { bearerAuth("tok-po") }
        assertEquals(HttpStatusCode.OK, read.status)
    }

    @Test
    fun poReaffirmingFullAccessIsAllowed() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // A PO entry that keeps both read+write is not a lockout → allowed.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "po", canRead = true, canWrite = true))
        }
        assertEquals(HttpStatusCode.OK, put.status)
    }

    @Test
    fun workerToggleIsUnaffectedByPoGuard() = testApplication {
        application { installComm(config()) }
        val client = jsonClient()
        // Legitimate worker revoke must still succeed — the guard only protects the PO.
        val put = client.put("/api/acl") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AclEntry("po-backend", "backend", canRead = false, canWrite = false))
        }
        assertEquals(HttpStatusCode.OK, put.status)
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
