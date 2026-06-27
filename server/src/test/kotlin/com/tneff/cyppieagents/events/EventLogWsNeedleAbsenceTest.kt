package com.tneff.cyppieagents.events

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.connector.EventMasking
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.eventSocket
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CYP-44 §4 on the WS egress (CYP-40 `/ws/events`): the metadata-only Needle-Absence proof extended
 * to the live-tail stream. The needled corpus is projected and pushed live to an authorized operator;
 * BOTH needle classes (and the corpus's own `LEAK_` markers) must be absent from the streamed frames.
 *
 * §6 (operator-only fail-closed close 1008, subscribe-by-eventType, operator-receives) is owned and
 * mutation-proven by Backend's [EventSocketTest] — NOT duplicated here. This adds only the egress proof.
 */
class EventLogWsNeedleAbsenceTest {

    private val registry = TokenRegistry(mapOf("tok-be" to "backend"), operatorToken = "tok-op")

    private fun ApplicationTestBuilder.serve(sink: InMemoryEventSink) {
        install(WebSockets)
        routing { eventSocket(sink, registry) }
    }

    private fun ApplicationTestBuilder.wsClient() = createClient { install(ClientWebSockets) }

    private fun corpus(): List<StreamJsonEvent> =
        (javaClass.getResourceAsStream("/streamjson/corpus.ndjson")
            ?: error("corpus.ndjson not found on the test classpath"))
            .bufferedReader().readLines().filter { it.isNotBlank() }
            .map { CommJson.decodeFromString<StreamJsonEvent>(it) }

    /** §4 recipe: inject 2-class needle → mask (real tap) → project (structural metadata-only). */
    private fun projectNeedledCorpus(): List<EventDraft> {
        val projector = EventProjector(ContextUsageBander(), teamId = "team")
        return corpus().flatMap { ev ->
            val masked = EventMasking.mask(NeedleHarness.inject(ev))
            projector.project("backend", masked.sessionId, "corr-1", masked)
        }
    }

    @Test
    fun needlesAbsent_inWsEventsLiveStream() = testApplication {
        val sink = InMemoryEventSink(SystemTimeSource())
        serve(sink)
        val drafts = projectNeedledCorpus()
        assertTrue(drafts.isNotEmpty(), "corpus must project to events (else absence is vacuous)")

        wsClient().webSocket("/ws/events?token=tok-op") {
            val sb = StringBuilder()
            // Re-append the needled corpus until enough frames stream back — robust against the
            // subscribe-latency race (same trick as EventSocketTest); every re-pushed event is
            // freshly stamped but projected from the SAME needled corpus, so all are needle-free.
            withTimeout(5_000) {
                var got = 0
                while (got < drafts.size) {
                    sink.appendBatch(drafts)
                    val f = withTimeoutOrNull(100) { incoming.receive() }
                    if (f is Frame.Text) { sb.append(f.readText()).append('\n'); got++ }
                }
            }
            val stream = sb.toString()
            assertTrue(stream.contains("\"event\""), "non-vacuous: operator must have received live event pushes")
            NeedleHarness.assertNoNeedle(stream, "/ws/events live stream")
            assertFalse(stream.contains("LEAK_"), "no corpus LEAK_ content marker may reach the /ws/events stream")
            close()
        }
    }
}
