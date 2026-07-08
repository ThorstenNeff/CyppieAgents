package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.acl.AclHttpException
import com.tneff.cyppieagents.acl.AclLiveEvent
import com.tneff.cyppieagents.acl.AclRepository
import com.tneff.cyppieagents.acl.AclWsClient
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-317 INTEGRATION gate (my QA tooth) — the **real client `AclRepository` (PUT /api/acl) + real `AclWsClient`
 * (/ws/comm)** driven against the **real server** on [e2ePlatform]. Dev's two halves each fake the OTHER side:
 * `Cyp317NonMemberGrantE2eTest` drives the real server with a RAW `HttpClient` (not the client repo); the UI
 * `AclFlexibleGrantTest` drives the real VM/panel against a modeled `MembershipSyncHub` that emits `AclLiveEvent`
 * IN-MEMORY — bypassing the real `/ws/comm` broadcast AND its per-subscriber `canRead(entry.channelId)` filter
 * (CommRoutes.commEventForParticipant). That is the CYP-315/316 fake-shape class: the VM only flips a cell from
 * `pending` → `enforced` on the `/ws/comm` AclEvent ECHO (the source of truth, NOT the PUT-200); if the real server
 * did not actually deliver that echo to the OPERATOR's socket, the modeled test is green while the real grant hangs
 * in `pending` forever. This stitches the two real halves:
 *  - **full flow + ⭐ the echo reaches the operator:** real `AclRepository.setAcl` grants a NON-member → the real
 *    `AclWsClient` (operator) receives the real `EntryChanged` + membership-synced `ChannelsChanged` (pending→enforced),
 *    and the grantee actually SENDS/READS (enforcement);
 *  - **never fake-enforced over the wire:** before the grant there is no enforced entry and the non-member is 403 —
 *    the enforced truth exists ONLY after the real echo (the client can't fabricate it);
 *  - **guards over the real client repo:** a PO-lockout throws `AclHttpException(409, po_lockout_protected)` (→ the VM's
 *    `revert`), a non-operator throws `AclHttpException(403)` — nothing granted.
 *
 * (The VM/render — pending marker, non-member switches, `isMember`-gated enforced dot — extend `androidx.lifecycle`,
 * invisible to `:e2e`; same split as 315/316. Dev's `AclFlexibleGrantTest`/`AclPanelRenderTest` cover those over the
 * real composable; this proves the real client↔server seam the modeled hub stands in for.)
 */
class Cyp317AclNonMemberGrantClientWireTest {

    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"), SeedAgent("backend"))),
    )

    /** A bare client (WebSockets only) — `AclRepository`/`AclWsClient` add their own Bearer from the token arg. */
    private fun bare() = HttpClient(CIO) { install(ClientWebSockets) }

    private fun aclRepo(p: E2ePlatform, client: HttpClient, token: String) = AclRepository(client, p.baseUrl, token)

    private suspend fun HttpClient.send(base: String, channel: String, body: String): HttpResponse =
        post("$base/api/channels/$channel/messages") { contentType(ContentType.Application.Json); setBody(SendMessageRequest(body)) }

    /**
     * Full flow + ⭐ the enforced echo reaches the OPERATOR's real `/ws/comm` (the leg the modeled hub fakes).
     * backend is NOT a member of `po-frontend`; an operator grant must sync membership AND broadcast the echo the
     * operator's panel needs to flip the cell pending→enforced — then backend can really send/read.
     */
    @Test
    fun realGrantNonMember_echoesToOperatorWs_membershipSynced_andEnforced(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            val base = p.baseUrl
            val repoClient = bare()
            val wsClient = bare()
            try {
                val opRepo = aclRepo(p, repoClient, E2ePlatform.OPERATOR_TOKEN)

                // BEFORE: no enforced entry for backend on po-frontend, and the non-member is fail-closed (403).
                assertTrue(opRepo.acl("po-frontend", "backend").isEmpty(), "no enforced grant exists before the PUT")
                p.asAgent("backend").use { be ->
                    assertEquals(HttpStatusCode.Forbidden, be.send(base, "po-frontend", "pre").status, "non-member cannot send pre-grant")
                }

                // Operator opens the REAL /ws/comm ACL live source.
                val events = mutableListOf<AclLiveEvent>()
                val collector = launch {
                    AclWsClient(wsClient, p.wsBaseUrl, E2ePlatform.OPERATOR_TOKEN).events().collect { events.add(it) }
                }
                withTimeout(10_000) { while (events.none { it is AclLiveEvent.Connected }) delay(20) }
                // never fake-enforced: no EntryChanged for backend/po-frontend has arrived before the grant.
                assertTrue(
                    events.none { it is AclLiveEvent.EntryChanged && it.entry.agentId == "backend" && it.entry.channelId == "po-frontend" },
                    "no enforced echo exists before the grant (the cell can only be pending, never enforced)",
                )

                // GRANT via the REAL client repo (PUT /api/acl).
                val grant = AclEntry("po-frontend", "backend", canRead = true, canWrite = true, projectId = "default")
                val echoed = opRepo.setAcl(grant)
                assertEquals("backend" to "po-frontend", echoed.agentId to echoed.channelId, "the real repo decodes the echoed entry")

                // ⭐ the enforced echo REACHES the operator's socket — EntryChanged (grant) + membership-synced ChannelsChanged.
                // Sync-point barrier (NOT a widened timeout): the server's /ws/comm pump subscribes to the hub's
                // replay=0 SharedFlow ASYNCHRONOUSLY after the socket opens, so the first grant can out-run the
                // subscription and be dropped. `setAcl` is idempotent (backend is already a member after the first
                // call) — re-issue it until the echo lands; once the pump is live the next emit is delivered. A
                // genuinely-dropped echo (M1 no membership sync / M2 no AclEvent broadcast) never lands → still RED.
                fun entryEchoed() = events.any { it is AclLiveEvent.EntryChanged && it.entry.agentId == "backend" && it.entry.channelId == "po-frontend" && it.entry.canRead && it.entry.canWrite }
                fun membershipEchoed() = events.filterIsInstance<AclLiveEvent.ChannelsChanged>().any { ev -> ev.channels.any { it.id == "po-frontend" && "backend" in it.members } }
                withTimeout(15_000) {
                    while (!entryEchoed() || !membershipEchoed()) {
                        opRepo.setAcl(grant) // idempotent re-broadcast until the (now-subscribed) pump delivers it
                        delay(150)
                    }
                }

                // ENFORCEMENT: the granted non-member now really sends AND reads.
                p.asAgent("backend").use { be ->
                    assertEquals(HttpStatusCode.Created, be.send(base, "po-frontend", "post-grant").status, "granted non-member can now send")
                    val msgs = be.get("$base/api/channels/po-frontend/messages").body<List<Message>>()
                    assertTrue(msgs.any { it.body == "post-grant" }, "granted non-member can now read")
                }
                collector.cancel()
            } finally { repoClient.close(); wsClient.close() }
        }
    }

    /**
     * Guards over the REAL client repo — the exact exceptions the VM's `revert` maps: a PO-lockout →
     * `AclHttpException(409, po_lockout_protected)`, a non-operator → `AclHttpException(403)`; neither grants anything.
     */
    @Test
    fun guards_poLockout409_and_nonOperator403_overRealClientRepo(): Unit = runBlocking {
        e2ePlatform(boot()).use { p ->
            val base = p.baseUrl
            val client = bare()
            try {
                // PO-lockout: the operator tries to strip the PO's own access on a hub channel → 409, nothing committed.
                val ex = assertFailsWith<AclHttpException> {
                    aclRepo(p, client, E2ePlatform.OPERATOR_TOKEN)
                        .setAcl(AclEntry("po-frontend", "po", canRead = false, canWrite = false, projectId = "default"))
                }
                assertEquals(409, ex.status, "PO-lockout is a 409 the VM maps to acl_po_protected")
                assertTrue(ex.bodyText.contains("po_lockout_protected"), "the reason code reaches the client: ${ex.bodyText}")
                p.asAgent("po").use { po ->
                    assertEquals(HttpStatusCode.Created, po.send(base, "po-frontend", "po retains").status, "PO retained write (nothing committed)")
                }

                // Non-operator: an agent token PUT /api/acl → 403 (anti-injection), nothing granted.
                val ex2 = assertFailsWith<AclHttpException> {
                    aclRepo(p, client, E2ePlatform.agentToken("frontend"))
                        .setAcl(AclEntry("po-frontend", "backend", canRead = true, canWrite = true, projectId = "default"))
                }
                assertEquals(403, ex2.status, "PUT /api/acl is operator-only")
                p.asAgent("backend").use { be ->
                    assertEquals(HttpStatusCode.Forbidden, be.send(base, "po-frontend", "nope").status, "the rejected grant changed nothing")
                }
            } finally { client.close() }
        }
    }
}
