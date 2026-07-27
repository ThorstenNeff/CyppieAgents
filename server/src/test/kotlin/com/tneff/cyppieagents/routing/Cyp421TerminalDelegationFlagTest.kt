package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.TerminalGrantRequest
import com.tneff.cyppieagents.model.TerminalGrants
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertTrue

/**
 * CYP-421 (c2) — the flag firewall for terminal member-delegation. THE FLAG IS THE ACCESS GATE IN CODE: a WORKING
 * `PUT /api/agents/{id}/terminal-grants` IS the ungated member-delegation past the Auftraggeber Access-Go, so the
 * whole grant surface sits behind `CYPPIE_TERMINAL_DELEGATION_ENABLED`. In the wiring, OFF ⇒ `admin == null`.
 *
 * The endpoint is always MOUNTED (contract honesty), so this test drives the firewall the wiring produces:
 *  - flag OFF (`admin == null`) ⇒ even an OPERATOR is denied `403 terminal_delegation_disabled` → merging (c)
 *    changes nothing observable. [flagOff_operatorPut_isDenied].
 *  - flag ON (a real store) ⇒ an operator grant persists and opens the socket gate. [flagOn_operatorPut_grants].
 *
 * Mutation: honor grants while the flag is off (e.g. `admin ?: InMemoryTerminalGrants()`) → the flag-off deny
 * flips to 200 and the grant would take effect → [flagOff_operatorPut_isDenied] reds. The flag-OFF SOCKET side
 * (a member denied via [NoTerminalGrants]) is covered by MemberTerminalDenyTest.
 */
class Cyp421TerminalDelegationFlagTest {

    private val tokens = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    private fun ApplicationTestBuilder.app(admin: TerminalGrantAdmin?) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) }
            }
            routing { terminalGrantRoutes(admin, tokens) }
        }
    }

    private fun ApplicationTestBuilder.client() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun flagOff_operatorPut_isDenied() = testApplication {
        app(admin = null) // flag OFF
        val res = client().put("/api/agents/backend/terminal-grants") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(TerminalGrantRequest("id-member"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("terminal_delegation_disabled", res.body<ApiErrorBody>().error.code, "flag OFF: even an operator is denied — the endpoint is inert")
    }

    @Test
    fun flagOn_operatorPut_grants_andPersists() = testApplication {
        val store = InMemoryTerminalGrants()
        app(admin = store) // flag ON
        val put = client().put("/api/agents/backend/terminal-grants") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(TerminalGrantRequest("id-member"))
        }
        assertEquals(HttpStatusCode.OK, put.status)
        assertEquals(listOf("id-member"), put.body<TerminalGrants>().subjects)
        assertTrue(store.mayOpen("backend", "id-member"), "flag ON: the operator's grant actually opens the socket gate")
        val get = client().get("/api/agents/backend/terminal-grants") { bearerAuth("tok-op") }
        assertEquals(listOf("id-member"), get.body<TerminalGrants>().subjects)
    }

    @Test
    fun nonOperator_isDenied_regardlessOfFlag() = testApplication {
        app(admin = InMemoryTerminalGrants()) // flag ON, but the surface is operator-only
        val res = client().put("/api/agents/backend/terminal-grants") {
            bearerAuth("tok-be"); contentType(ContentType.Application.Json); setBody(TerminalGrantRequest("id-member"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status, "an agent token (non-operator) cannot manage grants")
    }
}
