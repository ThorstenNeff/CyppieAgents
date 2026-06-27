package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.connector.EventMasking
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.installEvents
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * CYP-44 §4 — metadata-only **Needle-Absence**, end-to-end over the real `mask → project` chain
 * (CYP-37) against the canonical NDJSON corpus. Injects a two-class needle (secret-shaped + plain
 * content) into every content field, then asserts BOTH are absent in all egress surfaces:
 * projected events, the SQLite on-disk bytes, and the `/api/events` response. (`/ws/events` follows
 * with CYP-40.) Complements Backend's own `LEAK_` absence guard — the **plain-content** needle is the
 * adversarial proof above it (the masker can't catch it, so only structural exclusion can).
 */
class EventLogNeedleAbsenceTest {

    private fun corpus(): List<StreamJsonEvent> =
        (javaClass.getResourceAsStream("/streamjson/corpus.ndjson")
            ?: error("corpus.ndjson not found on the test classpath"))
            .bufferedReader().readLines().filter { it.isNotBlank() }
            .map { CommJson.decodeFromString<StreamJsonEvent>(it) }

    /** §4 recipe: inject needles into content → MASK (the real tap) → PROJECT (structural metadata-only). */
    private fun projectNeedledCorpus(): List<EventDraft> {
        val projector = EventProjector(ContextUsageBander(), teamId = "team")
        return corpus().flatMap { ev ->
            val masked = EventMasking.mask(NeedleHarness.inject(ev))
            projector.project("backend", masked.sessionId, "corr-1", masked)
        }
    }

    @Test
    fun needlesAbsent_inProjectedEvents_andNonVacuous() = runBlocking {
        // Non-vacuity: the PLAIN needle DOES survive masking into the projector's input — so if it's
        // absent downstream, the *projector* (not the masker) is what removed it. This is the crux.
        val maskedInput = corpus().joinToString("\n") {
            CommJson.encodeToString(StreamJsonEvent.serializer(), EventMasking.mask(NeedleHarness.inject(it)))
        }
        assertTrue(
            maskedInput.contains(NeedleHarness.PLAIN_NEEDLE),
            "plain needle must survive masking — else the absence proof is vacuous (the masker did the work, not the projector)",
        )

        val sink = InMemoryEventSink(SystemTimeSource())
        sink.appendBatch(projectNeedledCorpus())
        val events = sink.query(EventFilter.ALL, Page(limit = 1000)).events
        assertTrue(events.isNotEmpty(), "corpus must project to some events (else absence is vacuous)")

        val json = events.joinToString("\n") { CommJson.encodeToString(Event.serializer(), it) }
        NeedleHarness.assertNoNeedle(json, "projected events")
        assertFalse(json.contains("LEAK_"), "no corpus LEAK_ content marker may reach projected events")
    }

    @Test
    fun needlesAbsent_inSqliteOnDiskBytes() = runBlocking {
        val db = Files.createTempFile("needle-events", ".db").also { Files.deleteIfExists(it) }
        try {
            SqliteEventSink(db, SystemTimeSource()).use { sink ->
                sink.appendBatch(projectNeedledCorpus())
                sink.query(EventFilter.ALL, Page(limit = 1000)) // force a read path too
            }
            // Grep EVERY on-disk file (db + -wal + -shm) so WAL-buffered rows can't hide a leak.
            var checked = 0
            Files.newDirectoryStream(db.parent, "${db.fileName}*").use { ds ->
                for (f in ds) {
                    val bytes = Files.readAllBytes(f)
                    NeedleHarness.assertNoNeedle(bytes, "SQLite on-disk file ${f.fileName}")
                    assertFalse(bytes.decodeToString().contains("LEAK_"), "no LEAK_ marker in ${f.fileName}")
                    checked++
                }
            }
            assertTrue(checked > 0, "must have inspected at least the main db file")
        } finally {
            Files.newDirectoryStream(db.parent, "${db.fileName}*").use { ds -> ds.forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test
    fun needlesAbsent_inApiEventsResponse() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        application { installEvents(sink, TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")) }
        sink.appendBatch(projectNeedledCorpus())

        val resp = client.get("/api/events") { bearerAuth("tok-op") } // operator-only surface (CYP-39)
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        NeedleHarness.assertNoNeedle(body, "/api/events response")
        assertFalse(body.contains("LEAK_"), "no LEAK_ marker in /api/events response")
    }
}
