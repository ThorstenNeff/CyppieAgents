package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.AclEvent
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.MessageEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SubscribeEvents
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E2E Journey J2 (CYP-107) — Per-Project Isolation FOLLOWS the active switch, over the REAL embedded
 * platform. comm / ACL / event-log / config all re-scope to the active project without restart; the
 * other project's data is never visible/touched. Includes the CYP-103 class end-to-end
 * (`config_followSwitch_AuntouchedByBwrite`) and `subscribeCannotWidenPastActive`. Fail-closed: naming a
 * foreign channel yields empty (exact-match, no fall-through-open); a WS subscribe can't widen past the
 * operator's authorization. See `test/E2E-TEST-PLAN.md` §2/§8.
 */
class J2IsolationE2eTest {

    private fun twoProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("backend")), apiKey = "beta-secret-key-1234"),
        ),
    )

    @Test
    fun channels_followSwitch_AnotVisibleInB() = runBlocking {
        twoProjects().use { p ->
            val alpha = p.asOperator().use { it.get("${p.baseUrl}/api/channels").body<List<Channel>>() }
            assertEquals(listOf("po-frontend"), alpha.map { it.id })
            p.switchActive("beta")
            val beta = p.asOperator().use { it.get("${p.baseUrl}/api/channels").body<List<Channel>>() }
            assertEquals(listOf("po-backend"), beta.map { it.id })
            assertFalse(beta.any { it.id == "po-frontend" }, "alpha's channel is not visible while active=beta")
        }
    }

    @Test
    fun acl_followSwitch_noForeignEntriesInB() = runBlocking {
        twoProjects().use { p ->
            val alphaAcl = p.asOperator().use { it.get("${p.baseUrl}/api/acl").body<List<AclEntry>>() }
            assertTrue(alphaAcl.isNotEmpty() && alphaAcl.all { it.channelId == "po-frontend" })
            p.switchActive("beta")
            val betaAcl = p.asOperator().use { it.get("${p.baseUrl}/api/acl").body<List<AclEntry>>() }
            assertTrue(betaAcl.isNotEmpty() && betaAcl.all { it.channelId == "po-backend" })
            assertFalse(betaAcl.any { it.channelId == "po-frontend" }, "no alpha ACL entry leaks into beta scope")
        }
    }

    @Test
    fun events_followSwitch_onlyActiveProjectEvents_noForeignByteLeak() = runBlocking {
        twoProjects().use { p ->
            p.injectDroppedEvent("alpha")
            p.injectDroppedEvent("beta")
            // CYP-108 retrofit: structural check AND raw-byte foreign-projectId needle-absence.
            val alphaText = p.asOperator().use { it.get("${p.baseUrl}/api/events").assertNoNeedles("events active=alpha", foreignProjectIds = setOf("beta")) }
            assertTrue(CommJson.decodeFromString<com.tneff.cyppieagents.model.EventPage>(alphaText).events.let { it.isNotEmpty() && it.all { e -> e.projectId == "alpha" } }, "active=alpha → only alpha events")
            p.switchActive("beta")
            val betaText = p.asOperator().use { it.get("${p.baseUrl}/api/events").assertNoNeedles("events active=beta", foreignProjectIds = setOf("alpha")) }
            assertTrue(CommJson.decodeFromString<com.tneff.cyppieagents.model.EventPage>(betaText).events.let { it.isNotEmpty() && it.all { e -> e.projectId == "beta" } }, "active=beta → only beta events")
        }
    }

    @Test
    fun config_followSwitch_AuntouchedByBwrite() = runBlocking {
        twoProjects().use { p ->
            // active = alpha: set alpha's key through the real endpoint
            p.asOperator().use { c ->
                c.put("${p.baseUrl}/api/config/apikey") {
                    contentType(ContentType.Application.Json); setBody(ApiKeyRequest("alpha-secret-key-AAAA"))
                }
            }
            assertEquals("***AAAA", p.apiKeyMasked())
            // CYP-108 retrofit: the raw key must NEVER appear on the wire — only the masked tail.
            p.asOperator().use { it.get("${p.baseUrl}/api/config/apikey").assertNoNeedles("config apikey (active=alpha)", secrets = setOf("alpha-secret-key-AAAA")) }

            // switch to beta: config follows → beta's seeded key, NOT alpha's
            p.switchActive("beta")
            val betaView = p.asOperator().use { it.get("${p.baseUrl}/api/config/apikey").body<ApiKeyView>() }
            assertTrue(betaView.set); assertEquals("***1234", betaView.masked)

            // write beta's key, then switch back: alpha's key at rest is UNTOUCHED (the CYP-103 class e2e)
            p.asOperator().use { c ->
                c.put("${p.baseUrl}/api/config/apikey") {
                    contentType(ContentType.Application.Json); setBody(ApiKeyRequest("beta-secret-key-BBBB"))
                }
            }
            assertEquals("***BBBB", p.apiKeyMasked())
            p.switchActive("alpha")
            assertEquals("***AAAA", p.apiKeyMasked(), "alpha's key untouched by beta-context writes")
        }
    }

    @Test
    fun nameAchannelWhileActiveB_deniesForeign_failClosed() = runBlocking {
        twoProjects().use { p ->
            p.switchActive("beta")
            // po-frontend is alpha's channel; naming it while active=beta is denied 403 (exact-match miss →
            // not readable in scope), never served. Same 403 as an unknown channel → no existence leak.
            p.asOperator().use { c ->
                val r = c.get("${p.baseUrl}/api/channels/po-frontend/messages")
                assertEquals(HttpStatusCode.Forbidden, r.status, "foreign-project channel denied fail-closed, no fall-through-open")
            }
        }
    }

    @Test
    fun wsComm_reScopesOnReconnectAfterSwitch() = runBlocking {
        twoProjects().use { p ->
            assertEquals(listOf("po-frontend"), p.firstCommChannelSnapshot())
            p.switchActive("beta")
            assertEquals(listOf("po-backend"), p.firstCommChannelSnapshot())
        }
    }

    @Test
    fun wsEvents_subscribeCannotWidenPastActive_unauthorizedFallsBackToActive() = runBlocking {
        twoProjects().use { p ->
            p.switchActive("beta")
            val seen = mutableListOf<String>()
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/events") {
                    // narrow with an UNREGISTERED (unauthorized) projectId → resolver falls back to active (beta)
                    send(Frame.Text(CommJson.encodeToString(EventsWsClientEvent.serializer(), SubscribeEvents(projectId = "ghost"))))
                    delay(250) // let the re-pin process; base scope is already beta either way
                    p.injectDroppedEvent("alpha") // foreign → must be filtered
                    p.injectDroppedEvent("beta") // active → must arrive
                    withTimeout(4000) {
                        while (true) {
                            val ev = CommJson.decodeFromString<EventsWsServerEvent>((incoming.receive() as Frame.Text).readText())
                            if (ev is EventPushed) {
                                seen.add(ev.event.projectId)
                                if (ev.event.projectId == "beta") break
                            }
                        }
                    }
                }
            }
            assertTrue("beta" in seen, "active (beta) events still arrive")
            assertFalse("alpha" in seen, "unauthorized override 'ghost' cannot widen to alpha — fail-closed to active")
        }
    }

    /**
     * CYP-107 × CYP-246 — the AGENT SET follows the active switch. This is the isolation dimension J2 did NOT
     * cover: channels / ACL / events / config were each teethed over the switch above, but `/api/agents` was
     * not — and the agent set (with the window set) was the reported cross-project leak. Full-stack coverage:
     *  - a switch between two POPULATED projects shows each its OWN roster (alpha's worker never appears in beta);
     *  - a FRESH project created through the REAL `POST /api/projects` is 0 agents (the "fresh = 0 windows" repro);
     *  - switching back restores the stashed roster, and a repeat round-trip stays correct (re-stash-after-restore).
     *
     * Reload dimension (the part that slipped to prod on F5): a browser reload is a COLD, STATELESS `/api/agents`
     * GET. Every assertion here re-reads through a FRESH operator client ([agentIdSnapshot] opens a new client
     * per call), so a stale server-side roster would surface exactly as it did on reload — not hidden behind a
     * warm client cache. Mutation: drop the [com.tneff.cyppieagents.comm.HubState.rescope] agent-slice swap →
     * the boot roster stays live across every switch → the first post-switch assertion reddens.
     */
    @Test
    fun agentSet_followsSwitch_freshProjectIsEmpty_switchBackRestores_reloadDurable() = runBlocking {
        twoProjects().use { p ->
            assertEquals(setOf("po", "frontend"), p.agentIdSnapshot(), "active=alpha → alpha's roster")

            // switch to a DIFFERENT populated project → beta's OWN roster; alpha's worker never bleeds in
            p.switchActive("beta")
            val beta = p.agentIdSnapshot()
            assertEquals(setOf("po", "backend"), beta, "active=beta → beta's roster (backend)")
            assertFalse("frontend" in beta, "alpha's worker never leaks into beta across the switch")

            // create a FRESH project through the REAL API → switching to it yields 0 agents (no stale carry-over)
            p.createProject("gamma", "Gamma")
            p.switchActive("gamma")
            assertEquals(emptySet(), p.agentIdSnapshot(), "fresh project → 0 agents / 0 windows (the reported expectation)")

            // switch back → alpha's stashed roster restored intact
            p.switchActive("alpha")
            assertEquals(setOf("po", "frontend"), p.agentIdSnapshot(), "switch back → alpha's roster restored from the stash")

            // reload-durable across a REPEAT round-trip (lifecycle-aware, not a single happy switch): re-visit gamma
            // (still 0 — alpha's stash never bleeds) and alpha (still its roster — the re-stash-after-restore path)
            p.switchActive("gamma")
            assertEquals(emptySet(), p.agentIdSnapshot(), "second visit to gamma → still 0, alpha's stash never bleeds")
            p.switchActive("alpha")
            assertEquals(setOf("po", "frontend"), p.agentIdSnapshot(), "reload after round-trip → alpha's roster, never stale gamma/beta")
        }
    }

    /**
     * CYP-255 ① — the LIVE-SWITCH comm-egress money-tooth (2 AXES). A `/ws/comm` connection is HELD open
     * while the active project switches A→B; the global `state.acl` the pump filters through flips to B's
     * matrix. A foreign (project-A) event that reaches the pump AFTER the switch must be dropped fail-closed
     * on BOTH axes — the MessageEvent (message body) AND the AclEvent (ACL metadata).
     *
     * The scenario is sharpened so `canRead` ALONE cannot tell the two projects apart: both projects own a
     * channel of the SAME id (`po-frontend`, via [collidingProjects]) and the held participant (operator)
     * lingers as a member of B's same-id channel — so `canRead("po-frontend", operator)` is TRUE under B, and
     * ONLY the project gate (the event's own `projectId` ≠ active) can drop the A event. That project gate is
     * in `visibleMessages` (MessageEvent, merged in .4b①) but NOT YET in the AclEvent branch (still
     * `canRead`-only). Hence:
     *   • axis 1 (MessageEvent) runs against the `visibleMessages` pump (merged in .4b①) → GREEN;
     *   • axis 2 (AclEvent) runs against the monolith's `commEventForParticipant` AclEvent branch, now
     *     project-gated (`ProjectScope.permits(entry.projectId, active) AND canRead(...)` — the ①-AclEvent
     *     residual PO-Assistant flagged at the .4b① merge, closed here) → GREEN. Before the monolith this
     *     axis LEAKED (canRead-only); it exercises the pair's RUNTIME behavior, not just the unit teeth.
     * Full-stack sibling of CYP-254's unit collision tooth.
     *
     * The foreign A-event is staged with [injectBufferedMessageEvent]/[injectBufferedAclEvent] — the
     * deterministic analogue of an in-flight event created in A that reaches the pump after the switch (the
     * write API is fail-closed to the active project, so a foreign event can only be staged directly, exactly
     * as [injectDroppedEvent] stages an EventLog event). Each axis carries a same-project (B) POSITIVE CONTROL
     * that MUST arrive first, so the fail-closed `assertNull` means "filtered", never "delivery was broken".
     */
    @Test
    fun heldCommConn_afterSwitch_dropsForeignBufferedMessageEvent_axis1_body() = runBlocking {
        collidingProjects().use { p ->
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/comm") {
                    assertIs<ChannelsEvent>(nextCommEvent()) // initial snapshot (active = alpha)
                    delay(150) // let the server pump subscribe to the hub event stream
                    p.switchActive("beta") // held conn: global active flips → the pump now filters through beta's matrix

                    // POSITIVE CONTROL — an active-project (beta) message on the colliding channel DOES arrive, so
                    // the post-switch delivery path is proven live (the drop-assert below is not vacuously green).
                    p.injectBufferedMessageEvent(projectId = "beta", channelId = "po-frontend", from = "frontend", body = "beta-live")
                    val delivered = withTimeoutOrNull(2000) { nextMessageOrAcl() }
                    assertTrue(
                        delivered is MessageEvent && delivered.message.body == "beta-live",
                        "positive control: an active-project MessageEvent is delivered over the held, switched connection",
                    )

                    // AXIS 1 — a buffered ALPHA message (projectId=alpha) on the SAME channel id must NOT leak:
                    // visibleMessages gates the event's projectId first (alpha ≠ active beta) → dropped fail-closed,
                    // EVEN THOUGH canRead("po-frontend", operator) is true under beta.
                    p.injectBufferedMessageEvent(projectId = "alpha", channelId = "po-frontend", from = "frontend", body = "alpha-secret-body")
                    val leaked = withTimeoutOrNull(700) { nextMessageOrAcl() }
                    assertNull(
                        leaked,
                        "axis-1: a foreign (project-A) MessageEvent must not leak over a /ws/comm switched to B (visibleMessages projectId gate)",
                    )
                }
            }
        }
    }

    @Test
    fun heldCommConn_afterSwitch_dropsForeignBufferedAclEvent_axis2_aclMetadata() = runBlocking {
        collidingProjects().use { p ->
            p.asOperator().use { c ->
                c.webSocket("${p.wsBaseUrl}/ws/comm") {
                    assertIs<ChannelsEvent>(nextCommEvent()) // initial snapshot (active = alpha)
                    delay(150)
                    p.switchActive("beta")

                    // POSITIVE CONTROL — an active-project (beta) AclEvent on the colliding channel DOES arrive.
                    p.injectBufferedAclEvent(projectId = "beta", channelId = "po-frontend", agentId = "frontend")
                    val delivered = withTimeoutOrNull(2000) { nextMessageOrAcl() }
                    assertTrue(
                        delivered is AclEvent && delivered.entry.channelId == "po-frontend",
                        "positive control: an active-project AclEvent is delivered over the held, switched connection",
                    )

                    // AXIS 2 — a buffered ALPHA AclEvent (projectId=alpha) on the SAME channel id must NOT leak.
                    // Today the pump checks only canRead("po-frontend", operator) — TRUE under beta → it LEAKS;
                    // the projectId gate the monolith adds is what will drop it.
                    p.injectBufferedAclEvent(projectId = "alpha", channelId = "po-frontend", agentId = "frontend")
                    val leaked = withTimeoutOrNull(700) { nextMessageOrAcl() }
                    assertNull(
                        leaked,
                        "axis-2: a foreign (project-A) AclEvent must not leak over a /ws/comm switched to B (AclEvent projectId gate — the monolith residual)",
                    )
                }
            }
        }
    }

    // ---- helpers (all through the real path) ----

    /**
     * Two projects that BOTH own a `po-frontend` channel (each seeds a `frontend` worker) so a channel id
     * COLLIDES across the boundary and the held operator lingers as a member of B's same-id channel — the
     * precondition that makes `canRead` alone unable to distinguish the projects (only the projectId gate can).
     */
    private fun collidingProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "Alpha", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject("beta", "Beta", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
        ),
    )

    private suspend fun io.ktor.websocket.WebSocketSession.nextCommEvent(): CommWsServerEvent =
        CommJson.decodeFromString((incoming.receive() as Frame.Text).readText())

    /**
     * Next MessageEvent or AclEvent, skipping the re-scope ChannelsEvent(s) a switch may push. Suspends until
     * such an event arrives — wrap in `withTimeoutOrNull` to bound "nothing arrived" (= filtered fail-closed).
     */
    private suspend fun io.ktor.websocket.WebSocketSession.nextMessageOrAcl(): CommWsServerEvent {
        while (true) {
            val ev = nextCommEvent()
            if (ev is MessageEvent || ev is AclEvent) return ev
        }
    }

    /**
     * The hub's private live event flow — the `/ws/comm` pump collects it. Reached reflectively so a foreign
     * (non-active-project) event can be STAGED directly, the deterministic analogue of an in-flight event that
     * reaches the pump AFTER an active switch. The write API is fail-closed to the active project (a switched
     * connection cannot post a foreign-project event), exactly why [E2ePlatform.injectDroppedEvent] also stages
     * its EventLog event out-of-band. Test-only, zero production change. (A monolith-time alternative: an
     * `internal` test-emit seam on `Hub` — flagged to the coordinator.)
     */
    private fun E2ePlatform.hubEvents(): kotlinx.coroutines.flow.MutableSharedFlow<CommWsServerEvent> {
        val field = com.tneff.cyppieagents.comm.Hub::class.java.getDeclaredField("_events").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(booted.hub) as kotlinx.coroutines.flow.MutableSharedFlow<CommWsServerEvent>
    }

    private fun E2ePlatform.injectBufferedMessageEvent(projectId: String, channelId: String, from: String, body: String) {
        hubEvents().tryEmit(
            MessageEvent(Message(id = "m-$projectId-$channelId-$body", channelId = channelId, from = from, body = body, ts = 0L, projectId = projectId)),
        )
    }

    private fun E2ePlatform.injectBufferedAclEvent(projectId: String, channelId: String, agentId: String) {
        hubEvents().tryEmit(
            AclEvent(AclEntry(channelId = channelId, agentId = agentId, canRead = true, canWrite = false, projectId = projectId)),
        )
    }

    /** Cold, stateless roster read — a NEW operator client per call (the browser-reload analogue). */
    private suspend fun E2ePlatform.agentIdSnapshot(): Set<String> =
        asOperator().use { it.get("$baseUrl/api/agents").body<List<Agent>>() }.map { it.id }.toSet()

    /** Create a project through the REAL `POST /api/projects` (no production seam). */
    private suspend fun E2ePlatform.createProject(id: String, name: String) {
        asOperator().use { c ->
            c.post("$baseUrl/api/projects") {
                contentType(ContentType.Application.Json); setBody(CreateProjectRequest(id, name))
            }
        }
    }

    private suspend fun E2ePlatform.apiKeyMasked(): String? =
        asOperator().use { it.get("$baseUrl/api/config/apikey").body<ApiKeyView>().masked }

    private suspend fun E2ePlatform.firstCommChannelSnapshot(): List<String> {
        var ids: List<String> = emptyList()
        asOperator().use { c ->
            c.webSocket("$wsBaseUrl/ws/comm") {
                val ev = CommJson.decodeFromString<CommWsServerEvent>((incoming.receive() as Frame.Text).readText())
                ids = (ev as ChannelsEvent).channels.map { it.id }
            }
        }
        return ids
    }
}
