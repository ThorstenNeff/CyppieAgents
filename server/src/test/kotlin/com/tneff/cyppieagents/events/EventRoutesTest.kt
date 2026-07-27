package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installEvents
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ST5 (CYP-39) `/api/events`: MEMBER-tier fail-closed (CYP-186 BE2: secret-free metadata; was operator-only),
 * query-param filters (half-open `[since,until)`),
 * stable `seq`-paging, and strict 400s on unparseable filters. Driven over the real Ktor route against
 * a real in-memory sink, with a [ManualTimeSource] for deterministic `ts`.
 */
class EventRoutesTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op", loopbackPosture = true)

    private fun ApplicationTestBuilder.restClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    /** Three events with controlled ts/seq for filter + window assertions. */
    private suspend fun seed(sink: InMemoryEventSink, time: ManualTimeSource) {
        time.clock = 100; sink.append(draft(agent = "backend", type = EventType.TURN_START, severity = Severity.INFO))
        time.clock = 200; sink.append(draft(agent = "frontend", type = EventType.TOOL_CALL, severity = Severity.WARN, correlationId = "c1"))
        time.clock = 300; sink.append(draft(agent = "backend", type = EventType.ERROR_MODEL, severity = Severity.ERROR, sessionId = "s1"))
    }

    @Test
    fun memberReadable_failClosedWithoutCredential() = testApplication {
        val time = ManualTimeSource()
        val sink = InMemoryEventSink(time)
        application { installEvents(sink, registry) }
        val rest = restClient()
        seed(sink, time)

        // CYP-186 BE2: the event-log is secret-free metadata → MEMBER-readable (was operator-only). Still
        // fail-closed without a credential; an agent (MEMBER tier) may now read (forced to the active project).
        assertEquals(HttpStatusCode.Unauthorized, rest.get("/api/events").status, "no token → 401 (fail-closed)")
        assertEquals(HttpStatusCode.OK, rest.get("/api/events") { bearerAuth("tok-be") }.status, "agent (MEMBER tier) → 200")
        val page: EventPage = rest.get("/api/events") { bearerAuth("tok-op") }.body()
        assertEquals(3, page.events.size, "operator sees the full team-wide log")
    }

    @Test
    fun filtersByEachAxis_andHalfOpenWindow() = testApplication {
        val time = ManualTimeSource()
        val sink = InMemoryEventSink(time)
        application { installEvents(sink, registry) }
        val rest = restClient()
        seed(sink, time)

        suspend fun ids(query: String): List<String> =
            rest.get("/api/events?$query") { bearerAuth("tok-op") }.body<EventPage>().events.map { it.agentId }

        assertEquals(listOf("backend", "backend"), ids("agentId=backend"))
        assertEquals(listOf("frontend"), ids("type=tool.call"))
        assertEquals(listOf("backend"), ids("severity=error"))
        assertEquals(listOf("frontend"), ids("correlationId=c1"))
        assertEquals(listOf("backend"), ids("sessionId=s1"))
        // half-open [since,until): ts ∈ [200,300) → only the frontend event at ts=200.
        assertEquals(listOf("frontend"), ids("since=200&until=300"))
        assertEquals(listOf("frontend", "backend"), ids("since=200")) // ts>=200 → ts 200,300
        assertEquals(listOf("backend", "frontend"), ids("until=300")) // ts<300 → ts 100,200
    }

    @Test
    fun pagesStablyOverSeq() = testApplication {
        val time = ManualTimeSource()
        val sink = InMemoryEventSink(time)
        application { installEvents(sink, registry) }
        val rest = restClient()
        repeat(5) { time.clock = (it + 1) * 10L; sink.append(draft(agent = "p", type = EventType.TURN_START)) }

        val p1: EventPage = rest.get("/api/events?limit=2") { bearerAuth("tok-op") }.body()
        assertEquals(2, p1.events.size)
        assertTrue(p1.hasMore)
        val p2: EventPage = rest.get("/api/events?limit=2&afterSeq=${p1.nextAfterSeq}") { bearerAuth("tok-op") }.body()
        assertEquals(2, p2.events.size)
        assertTrue(p2.hasMore)
        val p3: EventPage = rest.get("/api/events?limit=2&afterSeq=${p2.nextAfterSeq}") { bearerAuth("tok-op") }.body()
        assertEquals(1, p3.events.size)
        assertTrue(!p3.hasMore)
        assertEquals(null, p3.nextAfterSeq)
        // Concatenated pages = the full set, strictly increasing seq, no dup/gap.
        val seqs = (p1.events + p2.events + p3.events).map { it.seq }
        assertEquals((1L..5L).toList(), seqs)
    }

    @Test
    fun unparseableFilters_are400_notSilentlyEmpty() = testApplication {
        val sink = InMemoryEventSink(ManualTimeSource())
        application { installEvents(sink, registry) }
        val rest = restClient()

        assertEquals(HttpStatusCode.BadRequest, rest.get("/api/events?type=garbage") { bearerAuth("tok-op") }.status)
        assertEquals(HttpStatusCode.BadRequest, rest.get("/api/events?severity=loud") { bearerAuth("tok-op") }.status)
        assertEquals(HttpStatusCode.BadRequest, rest.get("/api/events?since=notanumber") { bearerAuth("tok-op") }.status)
        assertEquals(HttpStatusCode.BadRequest, rest.get("/api/events?limit=abc") { bearerAuth("tok-op") }.status)
        // `unknown` is the modelled sentinel → valid filter, not a 400.
        assertEquals(HttpStatusCode.OK, rest.get("/api/events?type=unknown") { bearerAuth("tok-op") }.status)
    }

    @Test
    fun limitIsClamped() = testApplication {
        val time = ManualTimeSource()
        val sink = InMemoryEventSink(time)
        application { installEvents(sink, registry) }
        val rest = restClient()
        repeat(3) { time.clock = (it + 1) * 10L; sink.append(draft(agent = "p")) }
        // limit=0 is coerced up to 1 (never a zero/negative page).
        val page: EventPage = rest.get("/api/events?limit=0") { bearerAuth("tok-op") }.body()
        assertEquals(1, page.events.size)
        assertTrue(page.hasMore)
    }
}
