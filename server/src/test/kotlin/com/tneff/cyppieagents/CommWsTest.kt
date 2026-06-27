package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import com.tneff.cyppieagents.routing.CommConfig
import com.tneff.cyppieagents.routing.installComm
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CommWsTest {

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

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private fun io.ktor.server.testing.ApplicationTestBuilder.wsClient() =
        createClient { install(ClientWebSockets) }

    private suspend fun io.ktor.websocket.WebSocketSession.nextEvent(): CommWsServerEvent =
        CommJson.decodeFromString((incoming.receive() as Frame.Text).readText())

    @Test
    fun pushesMessageToReadableParticipant() = testApplication {
        application { installComm(config()) }
        val rest = jsonClient()

        wsClient().webSocket("/ws/comm?token=tok-backend") {
            assertIs<ChannelsEvent>(nextEvent()) // initial snapshot
            delay(150) // let the server pump subscribe to the hub event stream

            rest.post("/api/channels/po-backend/messages") {
                bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
                setBody(SendMessageRequest("live!"))
            }
            val ev = assertIs<MessageEvent>(nextEvent())
            assertEquals("live!", ev.message.body)
            assertEquals("po-backend", ev.message.channelId)
        }
    }

    @Test
    fun doesNotPushUnreadableChannelToParticipant() = testApplication {
        application { installComm(config()) }
        val rest = jsonClient()

        wsClient().webSocket("/ws/comm?token=tok-frontend") {
            assertIs<ChannelsEvent>(nextEvent())
            delay(150)
            // a message in po-backend must NOT reach the frontend participant (ACL filter)
            rest.post("/api/channels/po-backend/messages") {
                bearerAuth("tok-backend"); contentType(ContentType.Application.Json)
                setBody(SendMessageRequest("secret to backend"))
            }
            val leaked = withTimeoutOrNull(600) { nextEvent() }
            assertNull(leaked, "frontend must not receive po-backend traffic")
        }
    }

    @Test
    fun operatorSeesAllChannelsLive() = testApplication {
        application { installComm(config()) }
        val rest = jsonClient()

        wsClient().webSocket("/ws/comm?token=tok-op") {
            val initial = assertIs<ChannelsEvent>(nextEvent())
            assertEquals(setOf("po-frontend", "po-backend"), initial.channels.map { it.id }.toSet())
            delay(150)
            rest.post("/api/channels/po-frontend/messages") {
                bearerAuth("tok-frontend"); contentType(ContentType.Application.Json)
                setBody(SendMessageRequest("hi po"))
            }
            val ev = assertIs<MessageEvent>(nextEvent())
            assertEquals("po-frontend", ev.message.channelId)
        }
    }

    @Test
    fun aclChangeIsPushedLive() = testApplication {
        application { installComm(config()) }
        val rest = jsonClient()

        wsClient().webSocket("/ws/comm?token=tok-op") {
            assertIs<ChannelsEvent>(nextEvent())
            delay(150)
            rest.put("/api/acl") {
                bearerAuth("tok-op"); contentType(ContentType.Application.Json)
                setBody(AclEntry("po-backend", "backend", canRead = true, canWrite = false))
            }
            // setAcl emits AclEvent then a refreshed ChannelsEvent.
            val acl = assertIs<AclEvent>(nextEvent())
            assertEquals("backend", acl.entry.agentId)
            assertEquals(false, acl.entry.canWrite)
        }
    }

    @Test
    fun doesNotPushAclEventForUnreadableChannel() = testApplication {
        application { installComm(config()) }
        val rest = jsonClient()

        wsClient().webSocket("/ws/comm?token=tok-frontend") {
            assertIs<ChannelsEvent>(nextEvent()) // initial
            delay(150)
            // Operator changes po-backend's ACL — frontend cannot read po-backend, so it must NOT
            // receive an AclEvent for it (cross-channel metadata leak). It only gets the re-scoped
            // ChannelsEvent (its own channels), never the AclEvent (which is emitted first).
            rest.put("/api/acl") {
                bearerAuth("tok-op"); contentType(ContentType.Application.Json)
                setBody(AclEntry("po-backend", "backend", canRead = true, canWrite = false))
            }
            val ev = nextEvent()
            assertIs<ChannelsEvent>(ev) // NOT an AclEvent → the AclEvent was filtered out
            assertEquals(listOf("po-frontend"), ev.channels.map { it.id })
        }
    }

    @Test
    fun noTokenIsFailClosed() = testApplication {
        application { installComm(config()) }
        wsClient().webSocket("/ws/comm") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }
}
