package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.comm.HubState
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
import kotlin.test.assertNotEquals
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
                // A minimal READ WS gated by wsReaderOrNull (the ticket-accepting tier): echoes the resolved subject.
                webSocket("/ws/probe") {
                    val subject = call.wsReaderOrNull(deps, reg)
                    if (subject == null) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")); return@webSocket }
                    send(Frame.Text(subject))
                }
                // A minimal DRIVE/operator WS gated by tokenAuthorize — the SAME predicate /ws/agent uses. It does
                // NOT consume tickets, so this proves a ticket can never unlock the operator/drive socket (no escalation).
                val driveAuth = tokenAuthorize(reg, deps)
                webSocket("/ws/drive") {
                    if (!driveAuth(call)) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized")); return@webSocket }
                    send(Frame.Text("drive-open"))
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

    /**
     * CYP-286 (PO1 re-gate sharpening) — the BINDING invariant, the crux behind the member-permitted classification:
     * a ticket is bound to the MINTER'S OWN principal (not a client field) and can only unlock the read WS scoped to
     * that principal — NEVER a foreign subject, NEVER the operator/drive socket. This is why a MEMBER minting is not an
     * escalation: the ticket carries the member's own read-subject, exactly what the member's session already grants.
     */
    @Test
    fun mint_bindsTicketToOwnPrincipal_andCannotOpenTheDriveWs() = testApplication {
        app()
        val http = httpClient()

        // (1) BINDING to own principal: each minter's ticket resolves to THAT minter's subject — the operator's to
        // OPERATOR_ID, the agent's to its agentId. The subject tracks the CREDENTIAL, never a client field.
        val opTicket = http.post("/api/ws-ticket") { bearerAuth("tok-op") }.body<WsTicket>().ticket
        val beTicket = http.post("/api/ws-ticket") { bearerAuth("tok-be") }.body<WsTicket>().ticket
        assertEquals(HubState.OPERATOR_ID, subjectOverWs("ticket=$opTicket"), "operator's ticket → OPERATOR_ID")
        assertEquals("backend", subjectOverWs("ticket=$beTicket"), "agent's ticket → its OWN agentId")

        // (2) NO CROSS-PRINCIPAL ESCALATION: the agent (a non-operator) minter's ticket is bound to "backend", NEVER
        // to OPERATOR_ID — a lower principal cannot obtain an operator-subject ticket (the same closure that admits a
        // MEMBER at requireCommReader binds that member's OWN identityId, so a member ticket is never wider).
        val beTicket2 = http.post("/api/ws-ticket") { bearerAuth("tok-be") }.body<WsTicket>().ticket
        assertNotEquals(HubState.OPERATOR_ID, subjectOverWs("ticket=$beTicket2"), "no escalation: an agent ticket is never the operator subject")

        // (3) NO ESCALATION TO THE OPERATOR/DRIVE WS: a control that a real operator TOKEN opens /ws/drive, then that a
        // valid read-tier TICKET presented to that same tokenAuthorize-gated socket (the /ws/agent predicate — it does
        // NOT consume tickets) unlocks NOTHING → closed fail-closed. Tickets are a read-tier transport ONLY.
        var driveViaToken: String? = null
        wsClient().webSocket("/ws/drive?token=tok-op") { driveViaToken = (incoming.receive() as Frame.Text).readText() }
        assertEquals("drive-open", driveViaToken, "control: a valid operator TOKEN opens the drive WS")

        val beTicket3 = http.post("/api/ws-ticket") { bearerAuth("tok-be") }.body<WsTicket>().ticket
        wsClient().webSocket("/ws/drive?ticket=$beTicket3") {
            assertEquals(
                CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code,
                "no escalation: a ticket cannot open the tokenAuthorize-gated drive/operator WS (read-tier transport only)",
            )
            close()
        }
    }
}
