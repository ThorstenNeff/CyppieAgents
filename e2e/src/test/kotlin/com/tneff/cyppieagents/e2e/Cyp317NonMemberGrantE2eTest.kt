package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-317 — an operator can grant ANY agent read/write on ANY channel, including a **non-member**, and the
 * hub enforces it. Membership IS the per-agent ACL (CYP-93/112): a grant (`canRead||canWrite`) auto-syncs
 * the grantee into `channel.members`, so `AclMatrix.canWrite/canRead` (member AND flag) then permits.
 *
 * Faithful, emulator-free, over the REAL platform + REST. Seed: po + frontend + backend → spokes
 * `po-frontend` (frontend's) and `po-backend`; **backend is NOT a member of `po-frontend`**. The guards
 * stay sharp: operator-only `PUT /api/acl`, and the PO-lockout guard (CYP-111/112).
 */
class Cyp317NonMemberGrantE2eTest {

    private fun platform() = e2ePlatform(
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"), SeedAgent("backend")))),
    )

    private suspend fun HttpClient.send(base: String, channel: String, body: String): HttpResponse =
        post("$base/api/channels/$channel/messages") { contentType(ContentType.Application.Json); setBody(SendMessageRequest(body)) }

    private suspend fun HttpClient.putAcl(base: String, entry: AclEntry): HttpResponse =
        put("$base/api/acl") { contentType(ContentType.Application.Json); setBody(entry) }

    @Test
    fun operatorGrantsNonMember_403before_thenSendAndReadEnforced_entryEchoed() = runBlocking {
        platform().use { p ->
            val base = p.baseUrl

            // BEFORE: backend is not a member of frontend's spoke → both send AND read are denied (fail-closed).
            p.asAgent("backend").use { be ->
                assertEquals(HttpStatusCode.Forbidden, be.send(base, "po-frontend", "pre-grant").status, "non-member cannot send")
                assertEquals(HttpStatusCode.Forbidden, be.get("$base/api/channels/po-frontend/messages").status, "non-member cannot read")
            }

            // GRANT: the operator gives backend read+write on po-frontend — a channel it is NOT in.
            val echoed = p.asOperator().use { op ->
                val r = op.putAcl(base, AclEntry("po-frontend", "backend", canRead = true, canWrite = true, projectId = "default"))
                assertEquals(HttpStatusCode.OK, r.status, "an operator grant to a NON-member is accepted (membership auto-syncs)")
                r.body<AclEntry>()
            }
            assertEquals("backend" to "po-frontend", echoed.agentId to echoed.channelId, "the entry is echoed")
            assertTrue(echoed.canRead && echoed.canWrite, "the echoed entry carries the grant")

            // AFTER: the hub ENFORCES the grant — backend can now send (canWrite) and read (canRead).
            p.asAgent("backend").use { be ->
                assertEquals(HttpStatusCode.Created, be.send(base, "po-frontend", "post-grant hello").status, "granted non-member can now SEND")
                val msgs = be.get("$base/api/channels/po-frontend/messages").body<List<DeliveredMessage>>().map { it.message }
                assertTrue(msgs.any { it.body == "post-grant hello" }, "granted non-member can now READ the channel")
            }
        }
    }

    @Test
    fun guards_stayScharf_putIsOperatorOnly_and_poLockoutStill409() = runBlocking {
        platform().use { p ->
            val base = p.baseUrl

            // Operator-only: a non-operator (agent token) PUT /api/acl → 403, nothing granted.
            p.asAgent("frontend").use { fe ->
                assertEquals(
                    HttpStatusCode.Forbidden,
                    fe.putAcl(base, AclEntry("po-frontend", "backend", canRead = true, canWrite = true, projectId = "default")).status,
                    "PUT /api/acl is operator-only (anti-injection)",
                )
            }
            // and the would-be grant did NOT take effect: backend still cannot send.
            p.asAgent("backend").use { be ->
                assertEquals(HttpStatusCode.Forbidden, be.send(base, "po-frontend", "should-not-send").status, "the rejected grant changed nothing")
            }

            // PO-lockout: revoking the PO's own access on its hub channel → 409, nothing committed.
            p.asOperator().use { op ->
                val r = op.putAcl(base, AclEntry("po-frontend", "po", canRead = false, canWrite = false, projectId = "default"))
                assertEquals(HttpStatusCode.Conflict, r.status, "the PO may not be locked out of a hub channel")
                assertEquals("po_lockout_protected", r.body<ApiErrorBody>().error.code)
            }
            // fail-closed: the PO retained write (the rejected lockout committed nothing).
            p.asAgent("po").use { po ->
                assertEquals(HttpStatusCode.Created, po.send(base, "po-frontend", "po still writes").status, "PO retained write after the rejected lockout")
            }
        }
    }
}
