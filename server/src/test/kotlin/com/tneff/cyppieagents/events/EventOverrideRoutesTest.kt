package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installEvents
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * S17 / CYP-94 — `/api/events` operator-only cross-project read override over the existing
 * `EventFilter.projectId` axis (CYP-102). Default stays forced-active; an authorized other project is
 * honored; an unauthorized id fails closed to active (never widens); `all` unscopes (MVP).
 */
class EventOverrideRoutesTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")
    private val authorized = setOf("alpha", "beta") // the operator's own projects (gamma is NOT authorized)

    private fun ApplicationTestBuilder.restClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private suspend fun seed(sink: InMemoryEventSink) {
        repeat(3) { sink.append(draft(agent = "a$it", team = "alpha")) }
        repeat(2) { sink.append(draft(agent = "b$it", team = "beta")) }
        sink.append(draft(agent = "g", team = "gamma")) // an outsider project, NOT in the authorized set
    }

    private fun ApplicationTestBuilder.serve(sink: InMemoryEventSink) =
        application { installEvents(sink, registry, activeProjectId = { "alpha" }, authorizedProjects = { authorized }) }

    private suspend fun page(rest: io.ktor.client.HttpClient, query: String): EventPage =
        rest.get("/api/events$query") { bearerAuth("tok-op") }.body()

    @Test fun defaultForcedActive_noParam() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource()); serve(sink); val rest = restClient(); runBlocking { seed(sink) }
        val p = page(rest, "")
        assertEquals(3, p.events.size, "no override → only the active (alpha) project")
        assertTrue(p.events.all { it.projectId == "alpha" })
    }

    @Test fun authorizedOverride_honored() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource()); serve(sink); val rest = restClient(); runBlocking { seed(sink) }
        val p = page(rest, "?projectId=beta")
        assertEquals(2, p.events.size, "authorized override → beta's events")
        assertTrue(p.events.all { it.projectId == "beta" })
    }

    @Test fun unauthorizedOverride_failsClosedToActive() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource()); serve(sink); val rest = restClient(); runBlocking { seed(sink) }
        // gamma is a real project's events but NOT in the operator's authorized set → must NOT widen
        val p = page(rest, "?projectId=gamma")
        assertTrue(p.events.none { it.projectId == "gamma" }, "unauthorized override never reaches gamma")
        assertTrue(p.events.all { it.projectId == "alpha" }, "fails closed to active (alpha), not gamma")
    }

    @Test fun all_unscopes_mvp() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource()); serve(sink); val rest = restClient(); runBlocking { seed(sink) }
        val p = page(rest, "?projectId=all")
        // MVP single-tenant: all = unscoped → every project's events (incl. the outsider). S18 binds to authorized set.
        assertEquals(6, p.events.size, "all → unscoped across projects (MVP)")
    }
}
