package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.MessageInput
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.hubMcpRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2.4 / CYP-140 — the trust boundary on the **remote send path** (`/mcp/hub`, the foreign connector's
 * entry). The agent's data is *data, not authority*:
 *  - **A1 identity:** a spoofed `from` in the args is ignored — the post is stamped as the BOUND agent
 *    (the typed `HubSendCommand` carries no `from`; identity is server-bound, never an argument).
 *  - **A5 DoS:** the send path is size-capped (CYP-143 reuse) — oversized bodies are rejected, not posted.
 *
 * (A3 authz — forbidden channel → tool error, no post — is pinned by `HubMcpRoutesTest`; A2 tenant is
 * structural: `postAsAgent` stamps `projectId`, the args can't carry one.)
 */
class TrustBoundaryTest {

    private fun agents() = listOf(
        Agent("po", "PO", Role.PO, "po"),
        Agent("backend", "BE", Role.WORKER, "backend"),
    )
    private fun hub() = Hub(HubState.hubAndSpoke(agents(), HubState.OPERATOR_ID), InMemoryMessageStore())
    private fun registry() = TokenRegistry(mapOf("tok-po" to "po", "tok-backend" to "backend"), operatorToken = "tok-op")

    private fun ApplicationTestBuilder.installApp(hub: Hub, registry: TokenRegistry) {
        application {
            install(ServerContentNegotiation) { json(CommJson) }
            routing { hubMcpRoutes(hub, registry) }
        }
    }
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun a1_spoofedFromInArgsIsIgnored_postIsTheBoundAgent() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        // The agent (token → "po") sends, but TRIES to spoof from="operator" in the args.
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_send","arguments":{"channel":"po-backend","text":"hi","from":"operator"}}}""")
        }
        val body: JsonObject = res.body()
        assertEquals(false, body["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        val posted = hub.channelMessages("po", "po-backend")
        assertEquals(1, posted.size)
        assertEquals("po", posted.first().from, "identity is the bound agent — the spoofed `from` arg is ignored")
    }

    @Test
    fun a5_oversizedBodyIsRejected_notPosted() = testApplication {
        val hub = hub()
        installApp(hub, registry())
        val oversized = "x".repeat(MessageInput.MAX_BODY_CHARS + 1)
        val res = jsonClient().post("/mcp/hub") {
            bearerAuth("tok-po"); contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hub_send","arguments":{"channel":"po-backend","text":"$oversized"}}}""")
        }
        val body: JsonObject = res.body()
        assertTrue(body["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean(), "oversized → tool error")
        assertTrue(hub.channelMessages("po", "po-backend").isEmpty(), "nothing persisted on the remote send path")
    }
}
