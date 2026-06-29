package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.model.EventPushed
import com.tneff.cyppieagents.model.EventsWsClientEvent
import com.tneff.cyppieagents.model.EventsWsServerEvent
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SubscribeEvents
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun events_followSwitch_onlyActiveProjectEvents() = runBlocking {
        twoProjects().use { p ->
            p.injectDroppedEvent("alpha")
            p.injectDroppedEvent("beta")
            val alphaEvents = p.asOperator().use { it.get("${p.baseUrl}/api/events").body<com.tneff.cyppieagents.model.EventPage>().events }
            assertTrue(alphaEvents.isNotEmpty() && alphaEvents.all { it.projectId == "alpha" }, "active=alpha → only alpha events")
            p.switchActive("beta")
            val betaEvents = p.asOperator().use { it.get("${p.baseUrl}/api/events").body<com.tneff.cyppieagents.model.EventPage>().events }
            assertTrue(betaEvents.isNotEmpty() && betaEvents.all { it.projectId == "beta" }, "active=beta → only beta events")
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

    // ---- helpers (all through the real path) ----

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
