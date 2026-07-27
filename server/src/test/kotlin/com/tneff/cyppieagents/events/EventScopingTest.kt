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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * S13 / CYP-102 — Event-Log scoping by active projectId. The Event-Log is a SHARED multi-project store,
 * so `/api/events` must show ONLY the active project's events (no-cross-project leak), the view must
 * follow a project switch without restart, and a blank active project must fail CLOSED (deny → empty,
 * not "all"). Proven at the sink layer (both impls — the `EventFilter.projectId` chokepoint) AND at the
 * route layer (server-side resolution from the active pointer).
 */
class EventScopingTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    private fun ApplicationTestBuilder.restClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private suspend fun seedAB(sink: EventSink) {
        repeat(3) { sink.append(draft(agent = "a$it", team = "alpha")) }
        repeat(2) { sink.append(draft(agent = "b$it", team = "beta")) }
    }

    // ---- sink layer: EventFilter.projectId is the scope chokepoint (both impls) ----

    private suspend fun assertSinkScopes(sink: EventSink) {
        seedAB(sink)
        val alpha = sink.query(EventFilter(projectId = "alpha"), Page(limit = 100)).events
        assertEquals(3, alpha.size, "only alpha events")
        assertTrue(alpha.all { it.projectId == "alpha" }, "no-cross-project: no beta event leaks into alpha scope")
        assertEquals(2, sink.query(EventFilter(projectId = "beta"), Page(limit = 100)).events.size, "switching scope flips the set")
        // fail-closed: a blank projectId scope matches no real project's events (deny, not "all").
        assertEquals(0, sink.query(EventFilter(projectId = ""), Page(limit = 100)).events.size, "blank scope → empty")
    }

    @Test fun inMemorySink_scopesByProject() = runBlocking { assertSinkScopes(InMemoryEventSink(SystemTimeSource())) }

    @Test fun sqliteSink_scopesByProject() = runBlocking {
        val dir = Files.createTempDirectory("ev-scope-cyp102").toFile()
        try {
            SqliteEventSink(dir.resolve("events.db").toPath(), SystemTimeSource()).use { assertSinkScopes(it) }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- route layer: /api/events scopes to the active pointer, switch re-scopes, blank fails closed ----

    @Test fun apiEvents_showsOnlyActiveProject_andSwitchRescopes() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        var active = "alpha" // a mutable active pointer the route resolves per request (like the registry)
        application { installEvents(sink, registry, activeProjectId = { active }) }
        val rest = restClient()
        seedAB(sink)

        val alpha: EventPage = rest.get("/api/events") { bearerAuth("tok-op") }.body()
        assertEquals(3, alpha.events.size, "active=alpha → only alpha's 3 events")
        assertTrue(alpha.events.none { it.projectId == "beta" }, "no-cross-project leak of beta into alpha")

        active = "beta" // POST /api/projects/switch analogue — no restart
        val beta: EventPage = rest.get("/api/events") { bearerAuth("tok-op") }.body()
        assertEquals(2, beta.events.size, "switch changes the view without restart")
        assertTrue(beta.events.all { it.projectId == "beta" }, "now only beta")
    }

    @Test fun apiEvents_blankActive_failsClosed_empty() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        application { installEvents(sink, registry, activeProjectId = { "" }) } // misconfigured/blank active
        val rest = restClient()
        seedAB(sink)
        val page: EventPage = rest.get("/api/events") { bearerAuth("tok-op") }.body()
        assertEquals(0, page.events.size, "fail-closed: blank active denies everything, never leaks all")
    }
}
