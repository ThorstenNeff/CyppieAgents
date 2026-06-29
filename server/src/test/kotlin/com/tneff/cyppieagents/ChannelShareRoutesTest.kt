package com.tneff.cyppieagents

import com.tneff.cyppieagents.comm.ChannelShareStore
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.AuthorizeShareRequest
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelShareView
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.channelShareRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP contract for the cross-project channel-share endpoints (S17 / CYP-93). Reviewer focus: the
 * authorize/revoke act is operator/owner-gated and fail-closed (no token → no share); the disclosure
 * view is honest; the gate takes effect / closes via HubState.refreshShares.
 */
class ChannelShareRoutesTest {

    private val tokens = TokenRegistry(mapOf("tok-fe" to "frontend"), operatorToken = "tok-op")

    private fun fixture(): Pair<HubState, ChannelShareStore> {
        val channels = listOf(Channel("c", "c", ChannelKind.GROUP, listOf("a1", "b1"), projectId = "alpha"))
        val entries = listOf(
            AclEntry("c", "a1", canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("c", "b1", canRead = true, canWrite = false, projectId = "beta"),
        )
        val shares = ChannelShareStore(null, clock = { 7L })
        val state = HubState(emptyList(), channels, entries, activeProjectId = "alpha", operatorId = null) { pid ->
            shares.sharedInboundChannelIds(pid)
        }
        return state to shares
    }

    private fun ApplicationTestBuilder.app(state: HubState, shares: ChannelShareStore) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            routing { channelShareRoutes(state, shares, tokens) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    // ---- gating (fail-closed) ----

    @Test fun put_noToken_401_nothingShared() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        val r = jsonClient().put("/api/channels/c/share") {
            contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("beta")))
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(sh.record("c") == null, "fail-closed: no share authorized without a token")
    }

    @Test fun put_participant_403() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        val r = jsonClient().put("/api/channels/c/share") {
            bearerAuth("tok-fe"); contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("beta")))
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("operator_required", r.body<ApiErrorBody>().error.code)
        assertTrue(sh.record("c") == null, "fail-closed: participant cannot authorize a share")
    }

    @Test fun delete_participant_403() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        assertEquals(HttpStatusCode.Forbidden, jsonClient().delete("/api/channels/c/share") { bearerAuth("tok-fe") }.status)
    }

    // ---- happy / disclosure ----

    @Test fun get_notShared_byDefault() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        val v = jsonClient().get("/api/channels/c/share") { bearerAuth("tok-op") }.body<ChannelShareView>()
        assertFalse(v.shared, "fail-closed default: not shared")
    }

    @Test fun put_operator_shares_andDisclosesReach() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        val v = jsonClient().put("/api/channels/c/share") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("beta")))
        }.body<ChannelShareView>()
        assertTrue(v.shared)
        assertEquals(7L, v.sharedAt)
        // reachableScope = the concrete grantee agent (b1, project beta, read), NOT "project beta" wholesale
        assertEquals(listOf("b1"), v.reachableScope.map { it.agentId })
        assertEquals("beta", v.reachableScope.single().projectId)
    }

    @Test fun put_unknownChannel_404() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        val r = jsonClient().put("/api/channels/ghost/share") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("beta")))
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("channel_not_found", r.body<ApiErrorBody>().error.code)
    }

    @Test fun delete_operator_revokes() = testApplication {
        val (s, sh) = fixture(); app(s, sh)
        sh.share("c", "alpha", setOf("beta"))
        val v = jsonClient().delete("/api/channels/c/share") { bearerAuth("tok-op") }.body<ChannelShareView>()
        assertFalse(v.shared, "revoke → not shared (gate closed)")
        assertTrue(sh.record("c") == null)
    }
}
