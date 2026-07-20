package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.AgentConfigRegistry
import com.tneff.cyppieagents.boot.AgentManagement
import com.tneff.cyppieagents.boot.LifecycleManager
import com.tneff.cyppieagents.boot.RemoteTokenIssuer
import com.tneff.cyppieagents.boot.RemoteTokenStore
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.CreatedAgent
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.agentMgmtRoutes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-752 — the token-value BYTE-TOOTH for the remote-minted agent bearer (`CreatedAgent.token`, CYP-171).
 *
 * **Gap closed (test-honesty gap-map ①, SECRET-tier):** the API-key secret has a value-level byte-tooth
 * (`ConfigRoutesTest.apiKey_roundTrip_neverReturnsPlaintext` — `assertFalse(body.contains("supersecret"))`),
 * but the sibling one-time-mint credential — the remote agent token — was guarded only STRUCTURALLY
 * (`NoSecretInReadResponseTest` schema-walk: no FIELD named token/secret in a read schema) and OBJECT-level
 * (`AgentMgmtRoutesTest`: `assertNull(created.token)`). Neither catches the token VALUE smuggled into a
 * benignly-named read field (persona / worktree / launch / …). This asserts, against the REAL serialized
 * `AgentDetail` read body, that a freshly-minted token value never appears — the value-level analog of the
 * apiKey tooth, on the token path.
 *
 * **Scope (honest — don't over-harden where already pinned):** the participant-token sibling is ALREADY
 * byte-pinned (`ParticipantTokenRoutesTest`: mint → GET list → `assertFalse(list.contains(raw))`), and
 * `GET /api/agents` (list) returns `Agent`, which is structurally token-free + schema-walk-guarded. The one
 * unguarded surface is the `AgentDetail` read DTO, which carries benign STRING fields — so the tooth targets it.
 */
class Cyp752TokenValueByteToothTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private val registry = TokenRegistry(emptyMap(), operatorToken = "tok-op")
    private val store = RemoteTokenStore(null) // in-memory

    private fun mgmt(): AgentManagement {
        val state = HubState.hubAndSpoke(listOf(Agent("po", "PO", Role.PO, "po")), HubState.OPERATOR_ID, "default")
        val configs = AgentConfigRegistry(listOf(AgentConfig("po", "PO", Role.PO)))
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("po" to "po"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { id, _ -> FakeSession(id) },
        )
        return AgentManagement(
            state, lifecycle, configs,
            ensureWorktree = {}, deleteWorktree = {},
            remoteToken = RemoteTokenIssuer(registry, store),
        )
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.app(mgmt: AgentManagement) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            routing { agentMgmtRoutes(mgmt, registry) }
        }
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    /** A serialized read body must NOT carry the raw token value — the value-level analog of the apiKey tooth. */
    private fun assertNoTokenInBody(bodyText: String, token: String, where: String) =
        assertFalse(bodyText.contains(token), "$where must NOT contain the raw minted token value")

    @Test
    fun remoteMintedToken_neverAppearsIn_realAgentDetailReadBody() = testApplication {
        app(mgmt()); val c = jsonClient()

        // Mint a REAL remote-agent token via the real operator POST (the one-time disclosure, CYP-171).
        val create = c.post("/api/agents") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(NewAgentSpec("backend", "Backend", Role.WORKER, remote = true))
        }
        assertEquals(HttpStatusCode.Created, create.status)
        val createBody = create.bodyAsText()
        val token = CommJson.decodeFromString<CreatedAgent>(createBody).token
        assertNotNull(token, "a remote create discloses the minted token once (else this tooth is vacuous)")

        // NON-VACUITY WITNESS: the create body DOES carry the token once → proves the value is real AND that a
        // `contains(token)` check detects a token in a Ktor-serialized body, so the assertFalse below is live.
        assertTrue(createBody.contains(token), "the one-time mint disclosure must carry the token (non-vacuity)")
        assertEquals("backend", registry.agentFor(token), "the minted token resolves to the agent (a real credential)")

        // THE GUARD: the real serialized AgentDetail read must NOT carry the token value.
        val detail = c.get("/api/agents/backend") { bearerAuth("tok-op") }
        assertEquals(HttpStatusCode.OK, detail.status)
        assertNoTokenInBody(detail.bodyAsText(), token, "GET /api/agents/{id} (AgentDetail)")
    }

    /**
     * Permanent PROVE-RED (guard-helper non-vacuity): a deliberately-leaky read body that embeds a token value
     * into a benignly-named field must make [assertNoTokenInBody] FAIL. Mirrors `NoSecretInReadResponseTest`'s
     * LeakyReadDtoFixture — proves the helper is not vacuously green (it genuinely reds on a token-in-body).
     */
    @Test
    fun assertNoTokenInBody_redsOnALeakyBody() {
        val token = "sk-agent-abc123-LEAKED"
        val leakyBody = """{"id":"backend","name":"Backend","persona":"note:$token"}"""
        assertFailsWith<AssertionError> {
            assertNoTokenInBody(leakyBody, token, "leaky fixture")
        }
    }
}
