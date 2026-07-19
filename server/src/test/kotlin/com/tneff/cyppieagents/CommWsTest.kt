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
            assertEquals("live!", ev.delivered.message.body)
            assertEquals("po-backend", ev.delivered.message.channelId)
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
            assertEquals("po-frontend", ev.delivered.message.channelId)
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

    // CYP-255 ①-AclEvent — the /ws/comm pump's AclEvent branch must be PROJECT-scoped, not canRead-only:
    // an AclEvent stamped for project A (its entry.projectId) must NOT reach a participant on a connection
    // whose ACTIVE project is B, even on a channel-id collision the participant can read in the active
    // project. Tested against the extracted pure pump filter (deterministic, no live socket).
    // Non-vacuous: the SAME entry stamped for the ACTIVE project DOES pass (proves canRead is satisfied, so
    // the drop is specifically the project gate). Mutation: drop `ProjectScope.permits(...)` from the
    // AclEvent branch → the foreign-project event passes → this reds.
    @Test
    fun aclEvent_foreignProject_isDroppedByProjectGate_notCanReadAlone() {
        val agents = listOf(
            com.tneff.cyppieagents.model.Agent("po", "PO", com.tneff.cyppieagents.model.Role.PO, "po"),
            com.tneff.cyppieagents.model.Agent("backend", "BE", com.tneff.cyppieagents.model.Role.WORKER, "backend"),
        )
        // active project = "default"; operator is a member (canRead=true) of every channel.
        val state = com.tneff.cyppieagents.comm.HubState.hubAndSpoke(
            agents, com.tneff.cyppieagents.comm.HubState.OPERATOR_ID, "default",
        )
        val op = com.tneff.cyppieagents.comm.HubState.OPERATOR_ID
        val foreign = AclEvent(AclEntry("po-backend", "backend", canRead = true, canWrite = false, projectId = "other"))
        assertNull(
            com.tneff.cyppieagents.routing.commEventForParticipant(foreign, op, state, null),
            "an AclEvent from a foreign project must not reach an active-project participant (①-AclEvent)",
        )
        // Same channel + participant, but stamped for the ACTIVE project → passes (non-vacuous).
        val local = AclEvent(AclEntry("po-backend", "backend", canRead = true, canWrite = false, projectId = "default"))
        assertIs<AclEvent>(
            com.tneff.cyppieagents.routing.commEventForParticipant(local, op, state, null),
            "an active-project AclEvent the participant can read must pass (proves the drop above is the project gate, not canRead)",
        )
    }

    @Test
    fun noTokenIsFailClosed() = testApplication {
        application { installComm(config()) }
        wsClient().webSocket("/ws/comm") {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
    }
}
