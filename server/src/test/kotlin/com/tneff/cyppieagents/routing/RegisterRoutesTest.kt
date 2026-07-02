package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.KratosRegisterBackend
import com.tneff.cyppieagents.auth.RegisterMediator
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * CYP-179 / §B(b) — the register-wrapper HTTP contract: the actual WIRE response must be branch-invariant. This
 * is the §B closure at the observable boundary — a new-email and an existing-email register are indistinguishable
 * (identical status + body + no differing header), so the response reveals no account existence.
 */
class RegisterRoutesTest {

    private class FakeBackend(existing: Set<String>, private val failCheck: Boolean = false) : KratosRegisterBackend {
        private val store = existing.toMutableSet()
        override suspend fun identityExists(email: String): Boolean {
            if (failCheck) throw IllegalStateException("admin down"); return email in store
        }
        override suspend fun createAndVerify(email: String, password: String) { store += email }
        override suspend fun notifyExisting(email: String) {}
    }

    /** Mount only the register route (side-effects dropped — the RESPONSE is what this test pins). */
    private fun io.ktor.server.testing.ApplicationTestBuilder.mount(backend: KratosRegisterBackend) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            routing { registerRoutes(RegisterMediator(backend, dispatch = { /* off-path, dropped here */ })) }
        }
    }

    private suspend fun HttpResponse.dump() = "status=${status.value} body=${bodyAsText()} setCookie=${headers[HttpHeaders.SetCookie]}"

    @Test
    fun newAndExisting_areByteIdentical() = testApplication {
        mount(FakeBackend(existing = setOf("taken@x.com")))

        val fresh = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"fresh@x.com","password":"pw123456"}""")
        }
        val taken = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"taken@x.com","password":"pw123456"}""")
        }
        assertEquals(HttpStatusCode.OK, fresh.status)
        // The load-bearing parity: SAME status, SAME body, no branch-differing Set-Cookie.
        assertEquals(fresh.status, taken.status, "status must not reveal existence")
        assertEquals(fresh.bodyAsText(), taken.bodyAsText(), "body must not reveal existence — ${fresh.dump()} vs ${taken.dump()}")
        assertEquals(fresh.headers[HttpHeaders.SetCookie], taken.headers[HttpHeaders.SetCookie], "no branch-differing Set-Cookie")
        // And the body itself leaks nothing existence-revealing (no email echo, no Kratos conflict code / flow id).
        val body = fresh.bodyAsText()
        assertFalse(body.contains("taken@x.com") || body.contains("fresh@x.com"), "no email echo — $body")
        assertFalse(body.contains("4000007") || body.contains("exists") || body.contains("flow"), "no existence tell — $body")
        assertNull(fresh.headers[HttpHeaders.SetCookie], "the register handler sets no cookie of its own")
    }

    @Test
    fun adminOutage_uniform503_forBothBranches() = testApplication {
        mount(FakeBackend(existing = setOf("taken@x.com"), failCheck = true))

        val fresh = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"fresh@x.com","password":"pw123456"}""")
        }
        val taken = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"taken@x.com","password":"pw123456"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, fresh.status, "admin outage → 503 (fail-closed)")
        assertEquals(fresh.status, taken.status, "outage is uniform across branches (MUST-3)")
        assertEquals(fresh.bodyAsText(), taken.bodyAsText(), "uniform outage body")
    }

    @Test
    fun blankInput_400_isExistenceIndependent() = testApplication {
        mount(FakeBackend(existing = setOf("taken@x.com")))
        val r = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json); setBody("""{"email":"","password":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status, "malformed input → 400, same for everyone (not an oracle)")
    }
}
