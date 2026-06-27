package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
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
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/** CYP-18: the operator token is accepted on comm read/send as a privileged AclMatrix participant. */
class OperatorAccessTest {

    private fun config() = CommConfig(
        agents = listOf(
            Agent("po", "PO", Role.PO, "po"),
            Agent("frontend", "FE", Role.WORKER, "frontend"),
            Agent("backend", "BE", Role.WORKER, "backend"),
        ),
        tokens = mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
        store = InMemoryMessageStore(),
    )

    private fun io.ktor.server.testing.ApplicationTestBuilder.client() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun operatorSeesAllChannels() = testApplication {
        application { installComm(config()) }
        val channels: List<Channel> = client().get("/api/channels") { bearerAuth("tok-op") }.body()
        assertEquals(setOf("po-frontend", "po-backend"), channels.map { it.id }.toSet())
    }

    @Test
    fun agentRemainsScopedDespiteOperatorParticipation() = testApplication {
        application { installComm(config()) }
        val channels: List<Channel> = client().get("/api/channels") { bearerAuth("tok-backend") }.body()
        assertEquals(listOf("po-backend"), channels.map { it.id })
    }

    @Test
    fun operatorCanSendAsHumanInTheLoop() = testApplication {
        application { installComm(config()) }
        val rest = client()
        val created: Message = rest.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(SendMessageRequest("operator says hi"))
        }.body()
        assertEquals("operator", created.from) // sent as the operator participant, audited

        val read: List<Message> = rest.get("/api/channels/po-backend/messages") { bearerAuth("tok-backend") }.body()
        assertEquals(listOf("operator says hi"), read.map { it.body })
    }

    @Test
    fun operatorInboxAggregatesAllReadableChannels() = testApplication {
        application { installComm(config()) }
        val rest = client()
        rest.post("/api/channels/po-frontend/messages") {
            bearerAuth("tok-frontend"); contentType(ContentType.Application.Json); setBody(SendMessageRequest("a"))
        }
        rest.post("/api/channels/po-backend/messages") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json); setBody(SendMessageRequest("b"))
        }
        val inbox: List<Message> = rest.get("/api/inbox") { bearerAuth("tok-op") }.body()
        assertEquals(setOf("a", "b"), inbox.map { it.body }.toSet())
    }

    @Test
    fun operatorCanReadAnyChannelDirectly() = testApplication {
        application { installComm(config()) }
        val res = client().get("/api/channels/po-frontend/messages") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, res.status) // not 403
    }
}
