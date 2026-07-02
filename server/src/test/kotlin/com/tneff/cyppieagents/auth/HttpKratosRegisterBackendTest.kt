package com.tneff.cyppieagents.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-179 / C2 — hermetic wiring proof for [HttpKratosRegisterBackend] over a Ktor [MockEngine] (no live
 * Kratos). It pins the SEAM: which admin/public endpoints each op calls, in what order. It does NOT prove the
 * mail sends (that is the deploy Mailpit assertion in the runbook + `RegisterWrapperLiveProbeTest`); it catches
 * a typo'd path/payload before Staging. The mediator's MUST invariants are unchanged (proven in RegisterWrapperTest).
 */
class HttpKratosRegisterBackendTest {

    private data class Req(val method: String, val path: String, val query: String, val body: String?)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** A MockEngine that seeds [existing] emails and can force the admin-list / create status. */
    private fun backend(
        existing: Set<String> = emptySet(),
        listStatus: HttpStatusCode = HttpStatusCode.OK,
        createStatus: HttpStatusCode = HttpStatusCode.Created,
        captured: MutableList<Req>,
    ): HttpKratosRegisterBackend {
        val engine = MockEngine { request ->
            captured += Req(
                request.method.value, request.url.encodedPath, request.url.encodedQuery,
                (request.body as? TextContent)?.text,
            )
            val path = request.url.encodedPath
            when {
                path == "/admin/identities" && request.method == HttpMethod.Get -> {
                    if (listStatus != HttpStatusCode.OK) respond("[]", listStatus, jsonHeaders)
                    else {
                        val email = request.url.parameters["credentials_identifier"]
                        val arr = if (email in existing) """[{"id":"seed"}]""" else "[]"
                        respond(arr, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/admin/identities" && request.method == HttpMethod.Post ->
                    respond("""{"id":"new-id"}""", createStatus, jsonHeaders)
                path.endsWith("/self-service/verification/api") ->
                    respond("""{"id":"vf","ui":{"action":"http://public/self-service/verification?flow=vf","method":"POST"}}""", HttpStatusCode.OK, jsonHeaders)
                path.endsWith("/self-service/recovery/api") ->
                    respond("""{"id":"rf","ui":{"action":"http://public/self-service/recovery?flow=rf","method":"POST"}}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders) // the flow submits
            }
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        return HttpKratosRegisterBackend("http://admin", "http://public", client = client)
    }

    @Test
    fun identityExists_queriesByCredentialsIdentifier_andMapsPresence() = runBlocking {
        val reqs = mutableListOf<Req>()
        val b = backend(existing = setOf("taken@x.com"), captured = reqs)
        assertTrue(b.identityExists("taken@x.com"), "seeded email → exists")
        assertEquals(false, b.identityExists("fresh@x.com"), "unseeded email → not exists")
        assertTrue(reqs.all { it.path == "/admin/identities" && it.method == "GET" }, "only the admin list was hit")
        assertTrue(reqs.first().query.contains("credentials_identifier=taken"), "existence is queried by credentials_identifier — ${reqs.first().query}")
    }

    @Test
    fun identityExists_adminOutage_throws() {
        val reqs = mutableListOf<Req>()
        val b = backend(listStatus = HttpStatusCode.InternalServerError, captured = reqs)
        assertFailsWith<Exception>("a non-2xx admin list must throw → mediator UNAVAILABLE") {
            runBlocking { b.identityExists("x@x.com") }
        }
    }

    @Test
    fun createAndVerify_createsThenTriggersVerificationFlow() = runBlocking {
        val reqs = mutableListOf<Req>()
        backend(captured = reqs).createAndVerify("fresh@x.com", "pw-secret-123456")

        // 1) admin create with the email + password credential + active state; 2) verification flow init; 3) submit.
        assertEquals("POST", reqs[0].method); assertEquals("/admin/identities", reqs[0].path)
        assertTrue(reqs[0].body!!.contains("fresh@x.com") && reqs[0].body!!.contains("pw-secret-123456") && reqs[0].body!!.contains("active"),
            "create body carries email+password+state — ${reqs[0].body}")
        assertEquals("/self-service/verification/api", reqs[1].path, "verification flow initialized")
        assertEquals("/self-service/verification", reqs[2].path, "verification flow submitted (sends the mail)")
        assertTrue(reqs[2].body!!.contains("\"method\":\"code\"") && reqs[2].body!!.contains("fresh@x.com"),
            "submit carries method=code + email — ${reqs[2].body}")
    }

    @Test
    fun createAndVerify_conflict_skipsVerificationTrigger() = runBlocking {
        val reqs = mutableListOf<Req>()
        backend(createStatus = HttpStatusCode.Conflict, captured = reqs).createAndVerify("race@x.com", "pw123456")
        assertEquals(1, reqs.size, "on a 409 race, no verification flow is triggered — reqs: $reqs")
        assertEquals("/admin/identities", reqs[0].path)
    }

    @Test
    fun notifyExisting_triggersRecoveryFlow() = runBlocking {
        val reqs = mutableListOf<Req>()
        backend(captured = reqs).notifyExisting("taken@x.com")
        assertEquals("/self-service/recovery/api", reqs[0].path, "recovery flow initialized")
        assertEquals("/self-service/recovery", reqs[1].path, "recovery flow submitted (sends the notice mail)")
        assertTrue(reqs[1].body!!.contains("\"method\":\"code\"") && reqs[1].body!!.contains("taken@x.com"),
            "submit carries method=code + email — ${reqs[1].body}")
    }
}
