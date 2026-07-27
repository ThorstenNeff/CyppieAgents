package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.IdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.auth.SessionCredential
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.eventRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-240 (A) — the `/api/events` handler must reuse the principal the STRUCTURAL AuthGuard already resolved
 * (stashed under `PrincipalKey`), NOT re-resolve it. Before the fix the handler called `resolvePrincipal`
 * again = a SECOND live Kratos `whoami` per request, doubling this endpoint's exposure to the whoami race the
 * epic is about. This counts the idp resolutions for one authenticated GET: exactly ONE (the guard's).
 *
 * Mutation guard: revert the handler to `call.resolvePrincipal(deps)` and the count becomes 2 → RED.
 */
class EventsPrincipalReuseTest {

    /** Wraps an [IdentityProvider], counting every whoami resolution the request pipeline performs. */
    private class CountingIdentityProvider(private val delegate: IdentityProvider) : IdentityProvider {
        val count = AtomicInteger(0)
        override suspend fun resolve(credential: SessionCredential?): ResolvedIdentity? {
            count.incrementAndGet()
            return delegate.resolve(credential)
        }
    }

    @Test fun eventsGet_resolvesPrincipalExactlyOnce_reusesGuardStash() = testApplication {
        val registry = TokenRegistry(emptyMap(), operatorToken = "tok-op", loopbackPosture = true)
        // A verified human session (no bearer → the request takes the session axis, so the idp IS consulted).
        val idp = CountingIdentityProvider(FakeIdentityProvider(mapOf("sess-m" to ResolvedIdentity("mem-1", verified = true))))
        val deps = AuthDeps(registry, idp, InMemoryRoleStore(), { 1L })
        val sink = InMemoryEventSink(SystemTimeSource())
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(ApiError(cause.code, cause.message))) }
            }
            routing { eventRoutes(sink, registry, activeProjectId = { "alpha" }, authorizedProjects = { emptySet() }, deps = deps) }
        }
        val r = client.get("/api/events") { header("X-Session-Token", "sess-m") }
        assertEquals(HttpStatusCode.OK, r.status, "a verified session reads the event log at the MEMBER tier")
        assertEquals(1, idp.count.get(), "the handler must reuse the guard-stashed principal, not re-resolve (a 2nd whoami)")
    }
}
