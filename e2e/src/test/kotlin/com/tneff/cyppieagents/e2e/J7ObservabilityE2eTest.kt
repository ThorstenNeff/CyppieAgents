package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventType
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E Journey J7 (CYP-109) — Observability: Event-Log browse (paged, stable over `seq`) + live-tail,
 * drop-visibility via the deterministic `injectDroppedEvent` seam, and **metadata-only needle-absence
 * over REST AND WS** (the CYP-44 line e2e): a secret seeded as a project key must never appear in the
 * Event-Log on either surface, and a foreign-project id must not leak into an active-scoped read.
 *
 * DEFERRED (harness): Scanner/Warden (S11) stall events are produced from real agent output; the
 * FakeSpawner emits none, so those are unit-covered (ScannerScaffoldTest/StallDetectorTest). The
 * drop-visibility + metadata-only egress axes are fully exercised here via `injectDroppedEvent`.
 */
class J7ObservabilityE2eTest {

    private fun platform() = e2ePlatform(
        listOf(
            SeedProject("alpha", "A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject("beta", "B", listOf(SeedAgent("backend")), apiKey = Needles.SECRET),
        ),
    )

    @Test
    fun browse_paging_stableOverSeq_noDupNoGap() = runBlocking {
        platform().use { p ->
            repeat(5) { p.injectDroppedEvent("alpha") }
            p.asOperator().use { c ->
                val page1 = c.get("${p.baseUrl}/api/events?limit=2").body<EventPage>()
                assertEquals(2, page1.events.size)
                assertTrue(page1.hasMore && page1.nextAfterSeq != null, "more pages remain")
                val seqs1 = page1.events.map { it.seq }
                assertEquals(seqs1.sorted(), seqs1, "page ordered by seq ascending")

                val page2 = c.get("${p.baseUrl}/api/events?limit=2&afterSeq=${page1.nextAfterSeq}").body<EventPage>()
                assertTrue(page2.events.all { it.seq > page1.nextAfterSeq!! }, "cursor is strictly after the previous page")
                assertTrue(page2.events.map { it.seq }.none { it in seqs1 }, "no duplicate across pages (stable cursor)")
            }
        }
    }

    @Test
    fun dropVisibility_injectedDrop_surfaces_inBrowse() = runBlocking {
        platform().use { p ->
            p.injectDroppedEvent("alpha")
            p.asOperator().use { c ->
                val events = c.get("${p.baseUrl}/api/events").body<EventPage>().events
                assertTrue(events.any { it.type == EventType.LOG_DROPPED }, "an injected drop is visibly surfaced, not silently swallowed")
            }
        }
    }

    @Test
    fun dropVisibility_injectedDrop_surfaces_inLiveTail() = runBlocking {
        platform().use { p ->
            var sawDrop = false
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/events") {
                    delay(200)
                    p.injectDroppedEvent("alpha")
                    withTimeout(4000) {
                        while (true) {
                            val ev = CommJson.decodeFromString<EventsWsServerEvent>((incoming.receive() as Frame.Text).readText())
                            if (ev is EventPushed && ev.event.type == EventType.LOG_DROPPED) { sawDrop = true; break }
                        }
                    }
                    close()
                }
            }
            assertTrue(sawDrop, "the live tail pushes the injected drop")
        }
    }

    @Test
    fun metadataOnly_noSecretNoForeignLeak_overRestAndWs() = runBlocking {
        platform().use { p ->
            // SECRET is seeded as beta's apiKey; it must NEVER appear in the Event-Log on either surface.
            repeat(3) { p.injectDroppedEvent("alpha") }
            // REST (active = alpha): no secret, no foreign 'beta'
            p.asOperator().use { c ->
                c.get("${p.baseUrl}/api/events").assertNoNeedles("events REST (active=alpha)", foreignProjectIds = setOf("beta"))
            }
            // WS: collect the raw stream and grep it
            val raw = StringBuilder()
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/events") {
                    delay(200)
                    p.injectDroppedEvent("alpha")
                    runCatching {
                        withTimeout(2000) {
                            while (true) raw.append((incoming.receive() as Frame.Text).readText()).append('\n')
                        }
                    }
                    close()
                }
            }
            assertFalse(raw.isEmpty(), "the tail produced frames to inspect")
            assertNoNeedles("events WS (active=alpha)", raw.toString(), foreignProjectIds = setOf("beta"))
        }
    }
}
