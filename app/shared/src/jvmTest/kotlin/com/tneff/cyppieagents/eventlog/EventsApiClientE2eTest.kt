package com.tneff.cyppieagents.eventlog

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.Severity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-39 live-swap prep — e2e proof that [EventsApiClient] builds the right `/api/events` query string
 * from [EventFilter] + [Page] and decodes the `:core` [EventPage] response. Against a real embedded
 * Ktor server (not a fake), so the swap from [StubEventsApi] is only a constructor change.
 */
class EventsApiClientE2eTest {

    @Test
    fun query_buildsQueryString_andDecodesEventPage() = runBlocking {
        val seen = mutableMapOf<String, String?>()
        val page = EventPage(
            events = listOf(
                Event(id = "e1", ts = 1, seq = 1, agentId = "backend", projectId = "t", type = EventType.TURN_START, severity = Severity.INFO),
                Event(id = "e2", ts = 2, seq = 2, agentId = "backend", projectId = "t", type = EventType.RESULT_FINAL, severity = Severity.INFO),
            ),
            nextAfterSeq = 2,
            hasMore = false,
        )
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/events") {
                    val p = call.request.queryParameters
                    for (k in listOf("agentId", "type", "severity", "since", "until", "correlationId", "afterSeq", "limit")) {
                        seen[k] = p[k]
                    }
                    call.respondText(CommJson.encodeToString(EventPage.serializer(), page), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val api = EventsApiClient(client, "http://127.0.0.1:$port", token = "op")
                val result = api.query(
                    EventFilter(agentId = "backend", type = EventType.TURN_START, severity = Severity.WARN, since = 10, until = 20),
                    Page(afterSeq = 5, limit = 50),
                )

                // Decoded the :core EventPage correctly.
                assertEquals(listOf("e1", "e2"), result.events.map { it.id })
                assertEquals(2L, result.nextAfterSeq)
                assertEquals(false, result.hasMore)

                // Built the query string from filter + cursor (the CYP-39 contract surface).
                assertEquals("backend", seen["agentId"])
                assertEquals("turn.start", seen["type"])
                assertEquals("warn", seen["severity"])
                assertEquals("10", seen["since"])
                assertEquals("20", seen["until"])
                assertEquals("5", seen["afterSeq"])
                assertEquals("50", seen["limit"])
                assertTrue(seen["correlationId"] == null) // unset filters are omitted
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
