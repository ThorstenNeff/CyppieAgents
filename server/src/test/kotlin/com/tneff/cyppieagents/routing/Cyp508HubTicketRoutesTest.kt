package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.TokenPredicates
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.controlplane.CpJwtMinter
import com.tneff.cyppieagents.controlplane.HubRegistrar
import com.tneff.cyppieagents.controlplane.HubTicketFailure
import com.tneff.cyppieagents.controlplane.HubTicketMinter
import com.tneff.cyppieagents.controlplane.HubTicketRequest
import com.tneff.cyppieagents.controlplane.HubTicketResponse
import com.tneff.cyppieagents.controlplane.InertHubTicketMinter
import com.tneff.cyppieagents.controlplane.LiveHubTicketMinter
import com.tneff.cyppieagents.controlplane.RegisteredHub
import com.tneff.cyppieagents.crypto.RawKeys
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CYP-508 — the LIVE mint route re-gates the 4 enforcement points end-to-end (the Reviewer's LIVE gate). POINT 1
 * owner-check (non-owner → NOT_AUTHORIZED); POINT 2 `sub` = the authenticated principal (NOT the request); POINT 3
 * `cb` 1:1 (bound to the live `h` at the hub); POINT 4 TTL (CYP-503-proven; the token verifies within the TTL). The
 * identity-bearing points are proven **at the wire** through the merged S-E [CpJwtVerifier].
 */
class Cyp508HubTicketRoutesTest {

    private val opToken = "tok-op"
    private val hubId = "hub_abcdef0123456789"
    private val cp = RawKeys.generateEd25519()
    private val cpMinter = CpJwtMinter(cp.privateRaw, "cp1", "cp")
    private val pin: (String?) -> ByteArray? = { k -> if (k == "cp1") cp.publicRaw else null }
    private val now = 1_000_000_000_000L

    /** A LIVE minter whose registry has [hubId] owned by [ownerId]; the operator-token principal maps to `op-1`. */
    private fun liveMinter(ownerId: String) = LiveHubTicketMinter(
        authenticate = { it.sessionToken.ifBlank { null } },
        registrar = HubRegistrar(ConcurrentHashMap(mapOf(hubId to RegisteredHub(hubId, ownerId, "hub", 8787, "s", "d")))),
        cpJwtMinter = cpMinter,
        nowMs = { now },
    )

    private fun ApplicationTestBuilder.app(minter: HubTicketMinter) {
        val d = AuthDeps(TokenRegistry(emptyMap(), operatorToken = opToken), FakeIdentityProvider(emptyMap()), InMemoryRoleStore(), { now })
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) { exception<ApiException> { call, cause -> call.respond(cause.status) } }
            routing { hubTicketRoutes({ minter }, d.tokens, d, machineOperatorId = "op-1") }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test fun noCredential_is401_failClosed() = testApplication {
        app(liveMinter("op-1"))
        val r = jsonClient().post("/api/cp/hubticket") { contentType(ContentType.Application.Json); setBody(HubTicketRequest(hubId, "cb")) }
        assertEquals(HttpStatusCode.Unauthorized, r.status, "the mint route is operator-gated")
    }

    @Test fun inert_operator_getsNotAuthorized() = testApplication {
        app(InertHubTicketMinter)
        val r = jsonClient().post("/api/cp/hubticket") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody(HubTicketRequest(hubId, "cb"))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(HubTicketFailure.NOT_AUTHORIZED_FOR_HUB, r.body<HubTicketResponse>().failure, "INERT default mints nothing")
    }

    @Test fun live_owner_mints_subIsPrincipal_cbIs1to1_atTheWire() = testApplication {
        app(liveMinter("op-1")) // hub owned by op-1; the operator-token principal maps to op-1
        val h = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        val cb = TokenPredicates.expectedChannelBinding(h, hubId)
        val r = jsonClient().post("/api/cp/hubticket") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody(HubTicketRequest(hubId, cb))
        }.body<HubTicketResponse>()
        val token = assertNotNull(r.cpJwt, "the owner receives a minted hubTicket")
        // POINT 2 (sub = authenticated op-1) + POINT 3 (cb 1:1) + POINT 4 (within TTL) verified at the wire.
        val principal = CpJwtVerifier().verify(token, VerifierContext(hubId, "op-1", h, "cp", pin, now))
        assertIs<AuthPrincipal.Human>(principal)
        assertEquals("op-1", principal.identityId, "sub is the authenticated operator, not the request")
        // POINT 3: a DIFFERENT session h' → rejected (the cb binds the live h at the hub).
        assertNull(CpJwtVerifier().verify(token, VerifierContext(hubId, "op-1", byteArrayOf(1, 1, 1), "cp", pin, now)),
            "a wrong session h must not verify — cb is bound 1:1 to the request's live h")
    }

    @Test fun live_nonOwner_getsNotAuthorized() = testApplication {
        app(liveMinter("op-2")) // hub owned by op-2; the operator maps to op-1 → NOT the owner
        val r = jsonClient().post("/api/cp/hubticket") {
            header("Authorization", "Bearer $opToken"); contentType(ContentType.Application.Json); setBody(HubTicketRequest(hubId, "cb"))
        }.body<HubTicketResponse>()
        assertNull(r.cpJwt, "POINT 1: a non-owner is denied a hubTicket")
        assertEquals(HubTicketFailure.NOT_AUTHORIZED_FOR_HUB, r.failure)
    }
}
