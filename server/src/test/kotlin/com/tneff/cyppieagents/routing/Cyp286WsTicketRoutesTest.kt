package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.WsTicket
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CYP-286 — the `POST /api/ws-ticket` mint + the `?ticket=` acceptance at [wsReaderOrNull], end-to-end.
 *
 * The load-bearing teeth: an UNAUTHENTICATED mint is 401 (note 3); an authed mint's ticket opens a read WS via
 * `?ticket=`; the SAME ticket a second time is refused (ATOMIC single-use); and the ticket resolves to the SAME
 * subject the minter's own token does (NO escalation). Mutations: drop the `requireCommReader` gate on mint →
 * unauth mint stops 401-ing; `byHash[h]` instead of `remove` in consume → the reused ticket opens the WS.
 */
class Cyp286WsTicketRoutesTest {

    private val reg = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")

    /** ONE AuthDeps → mint and [wsReaderOrNull] share the SAME [AuthDeps.wsTickets] store. */
    private fun ApplicationTestBuilder.app(): AuthDeps {
        val deps = AuthDeps(reg)
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(WebSockets)
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) }
            }
            routing {
                wsTicketRoutes(reg, deps)
                // A minimal read WS gated by wsReaderOrNull: echoes the resolved subject, else closes fail-closed.
                webSocket("/ws/probe") {
                    val subject = call.wsReaderOrNull(deps, reg)
                    if (subject == null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")); return@webSocket }
                    send(Frame.Text(subject))
                }
            }
        }
        return deps
    }

    private fun ApplicationTestBuilder.httpClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }
    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    private suspend fun ApplicationTestBuilder.subjectOverWs(query: String): String? {
        var received: String? = null
        wsClient().webSocket("/ws/probe?$query") { received = (incoming.receive() as Frame.Text).readText() }
        return received
    }

    @Test
    fun unauthenticatedMint_is401() = testApplication {
        app()
        val res = httpClient().post("/api/ws-ticket") // no credential
        assertEquals(HttpStatusCode.Unauthorized, res.status, "note 3: an UNAUTHENTICATED mint must be 401")
    }

    @Test
    fun authedMint_ticketOpensReadWs_singleUse_noEscalation() = testApplication {
        app()
        val minted = httpClient().post("/api/ws-ticket") { bearerAuth("tok-be") }
        assertEquals(HttpStatusCode.Created, minted.status)
        val ticket = minted.body<WsTicket>().ticket

        // the ticket opens the read WS, resolving to the SAME subject the minter's own token does (no escalation)
        val viaTicket = subjectOverWs("ticket=$ticket")
        val viaToken = subjectOverWs("token=tok-be")
        assertNotNull(viaTicket, "the ticket opened the read WS")
        assertEquals(viaToken, viaTicket, "no escalation: the ticket resolves to the minter's OWN subject (== the token's)")

        // ATOMIC single-use: the same ticket a second time is refused
        wsClient().webSocket("/ws/probe?ticket=$ticket") {
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code,
                "single-use: a reused ticket must NOT open the WS (it was consumed on first resolve)",
            )
            close()
        }
    }
}
