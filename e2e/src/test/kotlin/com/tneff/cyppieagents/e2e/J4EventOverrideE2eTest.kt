package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.EventPage
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SubscribeEvents
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E Journey J4 (CYP-108) — operator cross-project Event-Log read override over the REAL platform, on
 * BOTH `/api/events` (`?projectId=`) and `/ws/events` (`SubscribeEvents.projectId`), through the one
 * `resolveEventScope` policy. Axes: no-override → active; an AUTHORIZED other project → that project;
 * `all` → the operator's projects; an UNAUTHORIZED/garbage id → **fail-closed to active** (no widen).
 * Read admission is **MEMBER-tier** (CYP-188 B): an agent/operator token is admitted; only a no/invalid
 * credential → 1008. The cross-project override stays OPERATOR-only, so a non-operator (agent) is
 * **forced-active** on both transports — that is the invariant J4 protects. Raw-byte needle-absence
 * ([assertNoNeedles]) at every active-scoped hop catches a leak the parsed-DTO checks would miss.
 *
 * The foreign project's id is [Needles.FOREIGN_PROJECT_ID] so its appearance in an active/unauthorized
 * scoped response is an unambiguous leak.
 */
class J4EventOverrideE2eTest {

    private val FOREIGN = Needles.FOREIGN_PROJECT_ID

    private fun platform() = e2ePlatform(
        listOf(
            SeedProject("proja", "Proj A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject(FOREIGN, "Foreign", listOf(SeedAgent("backend"))),
        ),
    )

    @Test
    fun rest_noOverride_active_only_andNoForeignLeak() = runBlocking {
        platform().use { p ->
            p.injectDroppedEvent("proja")
            p.injectDroppedEvent(FOREIGN)
            p.asOperator().use { c ->
                val text = c.get("${p.baseUrl}/api/events").assertNoNeedles("GET /api/events (active=proja)", foreignProjectIds = setOf(FOREIGN))
                val page = CommJson.decodeFromString<EventPage>(text)
                assertTrue(page.events.isNotEmpty() && page.events.all { it.projectId == "proja" }, "no override → active project only")
            }
        }
    }

    @Test
    fun rest_authorizedOtherProject_returnsThatProject() = runBlocking {
        platform().use { p ->
            p.injectDroppedEvent("proja")
            p.injectDroppedEvent(FOREIGN)
            p.asOperator().use { c ->
                // FOREIGN is a registered project → authorized → the operator may read it explicitly
                val page = CommJson.decodeFromString<EventPage>(c.get("${p.baseUrl}/api/events?projectId=$FOREIGN").bodyAsText())
                assertTrue(page.events.isNotEmpty() && page.events.all { it.projectId == FOREIGN }, "authorized override → that project's events")
            }
        }
    }

    @Test
    fun rest_all_returnsOperatorsProjects() = runBlocking {
        platform().use { p ->
            p.injectDroppedEvent("proja")
            p.injectDroppedEvent(FOREIGN)
            p.asOperator().use { c ->
                val page = CommJson.decodeFromString<EventPage>(c.get("${p.baseUrl}/api/events?projectId=all").bodyAsText())
                val ids = page.events.map { it.projectId }.toSet()
                assertTrue("proja" in ids && FOREIGN in ids, "all → both of the operator's projects (MVP single-tenant = unscoped)")
            }
        }
    }

    @Test
    fun rest_unauthorizedAndGarbage_failClosedToActive() = runBlocking {
        platform().use { p ->
            p.injectDroppedEvent("proja")
            p.injectDroppedEvent(FOREIGN)
            for (bad in listOf("ghostxyz", "notrealproj", "zzz999")) {
                p.asOperator().use { c ->
                    val text = c.get("${p.baseUrl}/api/events?projectId=$bad")
                        .assertNoNeedles("GET /api/events?projectId=$bad (must fall back to active)", foreignProjectIds = setOf(FOREIGN))
                    val page = CommJson.decodeFromString<EventPage>(text)
                    // Positive proof of →active (not just no-widen): the active project's events ARE present,
                    // so `all { proja }` is non-vacuous (an empty list would pass it for the wrong reason).
                    assertTrue(page.events.isNotEmpty(), "unauthorized/garbage '$bad' → falls back to the ACTIVE project (its events are returned), not an empty result")
                    assertTrue(page.events.all { it.projectId == "proja" }, "unauthorized/garbage '$bad' → fail-closed to active, never widens to FOREIGN")
                }
            }
        }
    }

    @Test
    fun rest_agentMember_readsActiveOnly_cannotCrossProjectOverride() = runBlocking {
        // CYP-218 (was `rest_operatorOnly_agentForbidden`, stale since CYP-186 / `ac140ed`): a MEMBER-tier agent
        // MAY now READ the Event-Log (200, not 403), but ONLY its ACTIVE project. The cross-project `?projectId`
        // override stays OPERATOR-only — a non-operator's authorized set is empty, so any override falls back to
        // **forced-active** (resolveEventScope). That forced-active invariant is what this still bites (kept
        // non-vacuous: the override request DOES return the active project's events, just never the foreign one).
        platform().use { p ->
            p.injectDroppedEvent("proja")
            p.injectDroppedEvent(FOREIGN)
            p.asAgent("frontend").use { c ->
                // CYP-186 posture: the agent/MEMBER reads its ACTIVE project's events.
                val plain = c.get("${p.baseUrl}/api/events")
                assertEquals(HttpStatusCode.OK, plain.status, "agent/MEMBER may READ the Event-Log (CYP-186), not 403")
                val plainPage = CommJson.decodeFromString<EventPage>(
                    plain.assertNoNeedles("GET /api/events as agent (active=proja)", foreignProjectIds = setOf(FOREIGN)),
                )
                assertTrue(plainPage.events.isNotEmpty() && plainPage.events.all { it.projectId == "proja" }, "agent sees its ACTIVE project's events only")

                // THE INVARIANT (still bites): a non-operator's `?projectId` override is NOT honored — forced-active,
                // never a cross-project read. Agent asking for FOREIGN → stays proja, no foreign leak.
                val over = c.get("${p.baseUrl}/api/events?projectId=$FOREIGN")
                assertEquals(HttpStatusCode.OK, over.status, "override doesn't 403 the agent — it silently forces active")
                val overPage = CommJson.decodeFromString<EventPage>(
                    over.assertNoNeedles("GET /api/events?projectId=FOREIGN as agent (must stay active)", foreignProjectIds = setOf(FOREIGN)),
                )
                assertTrue(overPage.events.isNotEmpty(), "override still returns the ACTIVE project's events (non-vacuous)")
                assertTrue(overPage.events.all { it.projectId == "proja" }, "agent cross-project override → fail-closed to active, never widens to FOREIGN")
            }
        }
    }

    @Test
    fun ws_noToken_1008_agentAdmitted_forcedActive_noForeignLeak() = runBlocking {
        // CYP-218 B (was `ws_operatorOnly_noToken_1008_agent_1008`, stale since CYP-188 B — and the SOURCE of
        // the J4 class hang: `wsEventsCloseCode(asAgent)` awaited a close frame that, post-admit, never comes →
        // indefinite block → 240s class timeout). Verify-first finding: TEST DEFECT, not a WS regress —
        // `EventSocket` deliberately admits MEMBER-tier now (matches `GET /api/events`). So:
        //  - no/invalid credential → still 1008 (that half was always right);
        //  - an agent is ADMITTED and streams its ACTIVE project only; its `?projectId` override is forced-active
        //    (non-operator authorized set empty) → the OPERATOR-only cross-project invariant this test guards.
        platform().use { p ->
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, p.wsEventsCloseCode(p.client(null)), "no token → 1008")

            // agent admitted (no close/hang); a FOREIGN override is forced-active → only proja streams, never FOREIGN.
            val seen = mutableListOf<String>()
            p.asAgent("frontend").use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/events") {
                    send(Frame.Text(CommJson.encodeToString(EventsWsClientEvent.serializer(), SubscribeEvents(projectId = FOREIGN))))
                    delay(200)
                    p.injectDroppedEvent(FOREIGN) // foreign first (FIFO): a leak would arrive before proja
                    p.injectDroppedEvent("proja")
                    withTimeout(4000) {
                        while (true) {
                            val ev = CommJson.decodeFromString<EventsWsServerEvent>((incoming.receive() as Frame.Text).readText())
                            if (ev is EventPushed) { seen.add(ev.event.projectId); if (ev.event.projectId == "proja") break }
                        }
                    }
                    close()
                }
            }
            assertTrue("proja" in seen, "agent is ADMITTED (CYP-188 B) and streams its active project")
            assertFalse(FOREIGN in seen, "agent `?projectId` override → forced-active, never a cross-project (FOREIGN) read")
        }
    }

    @Test
    fun ws_noOverride_active_only_noForeignLeak() = runBlocking {
        platform().use { p ->
            val seen = mutableListOf<String>()
            val raw = StringBuilder()
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/events") {
                    delay(200)
                    p.injectDroppedEvent(FOREIGN) // foreign first (FIFO): if it were to leak, it'd arrive before proja
                    p.injectDroppedEvent("proja")
                    withTimeout(4000) {
                        while (true) {
                            val t = (incoming.receive() as Frame.Text).readText()
                            raw.append(t).append('\n')
                            val ev = CommJson.decodeFromString<EventsWsServerEvent>(t)
                            if (ev is EventPushed) { seen.add(ev.event.projectId); if (ev.event.projectId == "proja") break }
                        }
                    }
                    close()
                }
            }
            assertTrue("proja" in seen, "active events stream")
            assertFalse(FOREIGN in seen, "no override → foreign events never pushed")
            assertNoNeedles("/ws/events (active=proja)", raw.toString(), foreignProjectIds = setOf(FOREIGN))
        }
    }

    @Test
    fun ws_authorizedOverride_streamsThatProject_unauthorizedStaysActive() = runBlocking {
        platform().use { p ->
            // authorized override → FOREIGN streams
            assertTrue(p.wsScopeReceives(subscribe = FOREIGN, inject = FOREIGN, breakOn = FOREIGN), "authorized override → that project streams")
            // unauthorized override → stays active (proja); foreign filtered
            val seen = mutableListOf<String>()
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/events") {
                    send(Frame.Text(CommJson.encodeToString(EventsWsClientEvent.serializer(), SubscribeEvents(projectId = "ghostxyz"))))
                    delay(200)
                    p.injectDroppedEvent(FOREIGN)
                    p.injectDroppedEvent("proja")
                    withTimeout(4000) {
                        while (true) {
                            val ev = CommJson.decodeFromString<EventsWsServerEvent>((incoming.receive() as Frame.Text).readText())
                            if (ev is EventPushed) { seen.add(ev.event.projectId); if (ev.event.projectId == "proja") break }
                        }
                    }
                    close()
                }
            }
            assertFalse(FOREIGN in seen, "unauthorized override 'ghostxyz' → fail-closed to active, no widen to FOREIGN")
        }
    }

    // ---- WS helpers (real handshakes) ----

    /** Connect `/ws/events` with [client] and return the server's close code (for the reject cases). */
    private suspend fun E2ePlatform.wsEventsCloseCode(client: HttpClient): Short {
        var code: Short = -1
        client.use { c ->
            c.webSocket("$wsBaseUrl/ws/events") {
                code = (closeReason.await()?.code) ?: -1
            }
        }
        return code
    }

    /** Subscribe with [subscribe], inject an event for [inject], and report whether a [breakOn]-project event arrives. */
    private suspend fun E2ePlatform.wsScopeReceives(subscribe: String, inject: String, breakOn: String): Boolean {
        var got = false
        asOperator().use { c ->
            c.webSocket("$wsBaseUrl/ws/events") {
                send(Frame.Text(CommJson.encodeToString(EventsWsClientEvent.serializer(), SubscribeEvents(projectId = subscribe))))
                delay(200)
                injectDroppedEvent(inject)
                withTimeout(4000) {
                    while (true) {
                        val ev = CommJson.decodeFromString<EventsWsServerEvent>((incoming.receive() as Frame.Text).readText())
                        if (ev is EventPushed && ev.event.projectId == breakOn) { got = true; break }
                    }
                }
                close()
            }
        }
        return got
    }
}
