package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CC1 / CYP-179 — hermetic end-to-end for [ConnectorCapabilityHttpRepository] against an embedded Ktor faking
 * `GET /api/agents`, now gated by `requireCommReader` (agent/operator token OR a verified human session), like
 * `/api/channels`. Proves the read carries the **same bearer the comm reads send** and parses caps + provider —
 * and that a 401 (the anonymous/wrong-credential case CC1 introduced) **fails closed to an empty read-model**,
 * never a faked-full one.
 */
class ConnectorCapabilityHttpRepositoryTest {

    private fun withServer(
        handler: io.ktor.server.routing.Routing.() -> Unit,
        token: String,
        block: suspend (ConnectorCapabilityHttpRepository) -> Unit,
    ) = runBlocking {
        val server = embeddedServer(Netty, port = 0) { routing { handler() } }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(ConnectorCapabilityHttpRepository(client, "http://127.0.0.1:$port", token))
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun read_sendsCredential_parsesCapsAndProviders() {
        val caps = defaultCapabilitiesFor(ConnectorKind.STREAM_JSON)
        val body = CommJson.encodeToString(
            ListSerializer(Agent.serializer()),
            listOf(
                Agent("backend", "Backend", Role.WORKER, "backend", AgentRunState.RUNNING,
                    capabilities = caps, provider = ProviderInfo("claude", "Claude")),
                // A second agent with neither field → absent from both maps (fail-closed by absence).
                Agent("po", "Product Owner", Role.PO, "po", AgentRunState.RUNNING),
            ),
        )
        withServer(handler = {
            get("/api/agents") {
                // ⭐ The gated server 401s the anonymous read; only the credentialed read is served. A mutation
                // that drops the Authorization header 401s here → the model comes back empty → the asserts below RED.
                if (call.request.header(HttpHeaders.Authorization) != "Bearer op") {
                    call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                } else {
                    call.respondText(body, ContentType.Application.Json)
                }
            }
        }, token = "op") { repo ->
            val model = repo.read()
            assertEquals(setOf("backend"), model.capabilities.keys)
            assertEquals(caps, model.capabilities["backend"])
            assertEquals(setOf("backend"), model.providers.keys)
            assertEquals("claude", model.providers["backend"]?.id)
        }
    }

    @Test
    fun read_unauthorized_failsClosedEmpty() {
        withServer(handler = {
            get("/api/agents") {
                call.respondText("""{"error":{"code":"unauthorized"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
            }
        }, token = "wrong") { repo ->
            val model = repo.read()
            assertTrue(model.capabilities.isEmpty(), "401 must yield no capabilities, never a faked-full model")
            assertTrue(model.providers.isEmpty(), "401 must yield no providers, never an invented one")
        }
    }
}
