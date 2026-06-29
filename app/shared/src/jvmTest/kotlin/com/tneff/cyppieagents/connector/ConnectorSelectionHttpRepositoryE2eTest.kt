package com.tneff.cyppieagents.connector

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ConnectorChoice
import com.tneff.cyppieagents.model.ConnectorKind
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-126 stub→real swap — e2e proof that [ConnectorSelectionHttpRepository] talks the CYP-122 connector
 * opt-in endpoint (`POST /api/agents/{id}/connector`) against a **real embedded Ktor server** (not a fake),
 * so the swap from [StubConnectorSelectionRepository] is only a constructor change. Covers:
 *  - **operator-gated, fail-closed** — without the operator token the server 403s and the repo throws
 *    `operator_required` (mirrors `requireOperator`, which runs before the body is received);
 *  - the **POST round-trip** — the body is the `:core` [ConnectorChoice] wire form `{ "connectorKind": "mcp" }`;
 *  - **404 mapping** — an unknown agent → `agent_not_found`;
 *  - the **defensive `agent_required`** when no agent id is supplied (no endpoint call at all).
 */
class ConnectorSelectionHttpRepositoryE2eTest {

    @Test
    fun operatorGated_postsConnectorChoice_mapsErrors() = runBlocking {
        var receivedBody: String? = null
        var receivedKind: ConnectorKind? = null

        fun err(code: String) = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, code)))

        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/agents/{id}/connector") {
                    // Fail-closed operator gate BEFORE the body is touched (mirrors requireOperator).
                    if (call.request.header("Authorization") != "Bearer op") {
                        call.respondText(err("operator_required"), ContentType.Application.Json, HttpStatusCode.Forbidden)
                        return@post
                    }
                    val id = call.parameters.getOrFail("id")
                    if (id == "ghost") {
                        call.respondText(err("agent_not_found"), ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@post
                    }
                    receivedBody = call.receiveText()
                    val choice = CommJson.decodeFromString(ConnectorChoice.serializer(), receivedBody!!)
                    receivedKind = choice.connectorKind
                    val updated = Agent(id, "Frontend", Role.WORKER, id, AgentRunState.RUNNING, connectorKind = choice.connectorKind)
                    call.respondText(CommJson.encodeToString(Agent.serializer(), updated), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val base = "http://127.0.0.1:$port"

                // 1. Operator-gated, fail-closed: no operator token → 403 → operator_required.
                val gated = ConnectorSelectionHttpRepository(client, base, operatorToken = "")
                val denied = assertFails { gated.activate("frontend", ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = true)) }
                assertIs<ConnectorException>(denied)
                assertEquals("operator_required", denied.code)
                assertNull(receivedKind, "the body must never be read when the operator gate fails")

                // 2. POST round-trip with the {connectorKind} wire form (the acknowledged B opt-in).
                val repo = ConnectorSelectionHttpRepository(client, base, operatorToken = "op")
                repo.activate("frontend", ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = true))
                assertEquals(ConnectorKind.MCP, receivedKind)
                assertTrue(receivedBody!!.contains("connectorKind"))
                assertTrue(receivedBody!!.contains("mcp")) // @SerialName wire value, not the enum name

                // 3. 404 → agent_not_found.
                val notFound = assertFails { repo.activate("ghost", ConnectorSelection(ConnectorKind.STREAM_JSON, riskAcknowledged = false)) }
                assertIs<ConnectorException>(notFound)
                assertEquals("agent_not_found", notFound.code)

                // 4. Defensive: a null agent id never reaches the endpoint.
                val missing = assertFails { repo.activate(null, ConnectorSelection(ConnectorKind.MCP, riskAcknowledged = true)) }
                assertIs<ConnectorException>(missing)
                assertEquals("agent_required", missing.code)
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
