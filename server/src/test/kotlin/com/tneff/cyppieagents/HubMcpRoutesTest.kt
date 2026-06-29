package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.hubMcpRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-146 — the in-process Hub MCP server (`POST /mcp/hub`). Token→agentId server-bound (no unauth path,
 * no foreign-agentId), `tools/call hub_send` routes ONLY through the Hub chokepoint (canWrite/stamps),
 * malformed args fail-closed. (The MCP handshake/version is exercised live at CYP-136; here we pin the
 * security-relevant routing.)
 */
class HubMcpRoutesTest {

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("frontend", "FE", Role.WORKER, "frontend"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )

    private fun hub() = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), InMemoryMessageStore())
    private fun registry() = TokenRegistry(
        mapOf("tok-po" to "po", "tok-frontend" to "frontend", "tok-backend" to "backend"),
        operatorToken = "tok-op",
    )

    private fun ApplicationTestBuilder.installApp(hub: Hub, registry: TokenRegistry) {
        application {
            install(ServerContentNegotiation) { json(CommJson) }
            routing { hubMcpRoutes(hub, registry) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private fun toolsCall(channel: String, text: String?) = buildString {
        append("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_send","arguments":{""")
        append(""""channel":"$channel"""")
        if (text != null) append(""","text":"$text"""")
        append("}}}")
    }

    @Test
    fun unauthenticatedIsRejected() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        val res = jsonClient().post("/mcp/hub") {
            contentType(ContentType.Application.Json); setBody(toolsCall("po-backend", "x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status) // no bearer → fail-closed
    }

    @Test
    fun operatorTokenCannotEmit() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(toolsCall("po-backend", "x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status) // operator is not an agent → cannot emit
    }

    @Test
    fun hubSendRoutesThroughTheChokepointAsTheBoundAgent() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json); setBody(toolsCall("po-backend", "do X"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        // The post went through Hub.postAsAgent as the BOUND agent (po), never an arg-claimed identity.
        val posted = hub.channelMessages("po", "po-backend")
        assertEquals(1, posted.size)
        assertEquals("po", posted.first().from)
        assertEquals("do X", posted.first().body)
    }

    @Test
    fun forbiddenChannelIsToolErrorNotAPost() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        // backend is NOT a member of po-frontend → canWrite=false → 403 surfaced as a tool error, no post.
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-backend"); contentType(ContentType.Application.Json); setBody(toolsCall("po-frontend", "intrusion"))
        }
        val body: JsonObject = res.body()
        assertTrue(body["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean(), "403 → isError")
        assertTrue(hub.channelMessages("po", "po-frontend").isEmpty(), "nothing persisted")
    }

    @Test
    fun malformedArgsFailClosed() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        // missing 'text' → fail-closed: tool error, nothing posted.
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json); setBody(toolsCall("po-backend", null))
        }
        val body: JsonObject = res.body()
        assertTrue(body["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(hub.channelMessages("po", "po-backend").isEmpty())
    }

    @Test
    fun toolsListExposesHubSend() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        }
        val body: JsonObject = res.body()
        val tools = body["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject["name"]!!.jsonPrimitive.content == "hub_send" })
    }
}
