package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.AuthorizeShareRequest
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.Message
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SendMessageRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E2E Journey J3 — grantee-READ axes (CYP-108 deferred, unblocked by CYP-111 + CYP-112), over the REAL
 * platform on develop `1553504`. Provisioning is real-path: the operator grants the grantee agent a
 * grantee-project-stamped read entry via `PUT /api/acl` — CYP-112 syncs `channel.members` so the agent
 * becomes a real member, CYP-111 no longer 409s — then authorizes the share. Each no-over-widen axis is
 * EXACT-equality and mutation-proven:
 *  shared channel visible to the member · the owner's neighbor channel NOT · a non-member NOT · revoke →
 *  immediately gone · no foreign AclEntry egress · grantee can actually READ the owner's messages.
 *
 * Owner `proja` (active boot): po + frontend + frontend2 → spokes `po-frontend`, `po-frontend2`.
 * Grantee `projb`: backend + backend2 → spokes `po-backend`, `po-backend2`.
 */
class J3CrossProjectReadE2eTest {

    private fun platform() = e2ePlatform(
        listOf(
            SeedProject("proja", "A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"), SeedAgent("frontend2"))),
            SeedProject("projb", "B", listOf(SeedAgent("backend"), SeedAgent("backend2")), apiKey = Needles.SECRET),
        ),
    )

    /**
     * CYP-218 (J3 provisioning rebuilt for the post-CYP-188 correct path — like J4). Order matters:
     *  1. the owner AUTHORIZES the share (`po-frontend` → `projb`) — this is the cross-project gate (CYP-93),
     *     putting `po-frontend` in projb's scope;
     *  2. SWITCH active → `projb`;
     *  3. THEN grant the grantee its read entry — `setAcl` stamps the ACTIVE project (CYP-188 `27fa831`:
     *     the client `AclEntry.projectId` is ignored, cross-project ACL injection is deliberately closed), so
     *     the entry must be written WHILE active=projb to be **projb-stamped** (survive the projb-scope filter)
     *     AND to sync `backend` into the now-in-scope shared channel's members (CYP-112). The OLD order (a
     *     `projectId="projb"` entry written while active=proja) is exactly what CYP-188 closed → the entry was
     *     re-stamped `proja` and dropped in projb scope → grantee member-but-can't-read. That was the stale rot.
     */
    private suspend fun E2ePlatform.provisionShareAndSwitch() {
        asOperator().use { c ->
            c.put("$baseUrl/api/channels/po-frontend/share") {
                contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("projb")))
            }
        }
        switchActive("projb")
        asOperator().use { c ->
            // projectId omitted — setAcl stamps activeProjectId (projb) server-side (CYP-188 single-source).
            c.put("$baseUrl/api/acl") {
                contentType(ContentType.Application.Json)
                setBody(AclEntry("po-frontend", "backend", canRead = true, canWrite = false))
            }
        }
    }

    private suspend fun E2ePlatform.channelIds(token: String): Set<String> =
        client(token).use { CommJson.decodeFromString<List<Channel>>(it.get("$baseUrl/api/channels").bodyAsText()).map { ch -> ch.id }.toSet() }

    @Test
    fun grantee_member_seesSharedChannel_neighborAndNonHubNot() = runBlocking {
        platform().use { p ->
            p.provisionShareAndSwitch()
            // EXACT: the grantee's own spoke + the shared channel; the owner's neighbor (po-frontend2) and
            // a projb spoke the grantee is not in (po-backend2) are NOT widened in.
            assertEquals(setOf("po-backend", "po-frontend"), p.channelIds(E2ePlatform.agentToken("backend")))
        }
    }

    @Test
    fun nonMember_doesNotSeeSharedChannel() = runBlocking {
        platform().use { p ->
            p.provisionShareAndSwitch()
            // backend2 was NOT provisioned onto po-frontend → it sees only its own spoke, never the shared one.
            assertEquals(setOf("po-backend2"), p.channelIds(E2ePlatform.agentToken("backend2")))
            // raw-byte: the shared channel id (which DOES exist + is shared) must not appear for the non-member.
            val text = p.asAgent("backend2").use { it.get("${p.baseUrl}/api/channels").bodyAsText() }
            assertNoNeedles("backend2 channels", text, foreignProjectIds = setOf("po-frontend"))
        }
    }

    @Test
    fun grantee_canReadOwnerMessages_crossProject() = runBlocking {
        platform().use { p ->
            // owner frontend posts to po-frontend while active = proja (member + canWrite)
            p.asAgent("frontend").use { c ->
                val r = c.post("${p.baseUrl}/api/channels/po-frontend/messages") {
                    contentType(ContentType.Application.Json); setBody(SendMessageRequest("hello-from-frontend"))
                }
                assertEquals(HttpStatusCode.Created, r.status)
            }
            p.provisionShareAndSwitch()
            // grantee backend reads the owner's channel ACROSS the boundary (200, not 403) and sees the message
            p.asAgent("backend").use { c ->
                val msgs = c.get("${p.baseUrl}/api/channels/po-frontend/messages").body<List<Message>>()
                assertTrue(msgs.any { it.body == "hello-from-frontend" }, "grantee reads the owner's cross-project message")
            }
        }
    }

    @Test
    fun revoke_immediatelyRemovesSharedChannelFromGranteeView() = runBlocking {
        platform().use { p ->
            p.provisionShareAndSwitch()
            assertTrue("po-frontend" in p.channelIds(E2ePlatform.agentToken("backend")), "shared before revoke")
            // revoke (operator) — the gate closes regardless of the lingering grantee entry
            p.switchActive("proja")
            p.asOperator().use { it.delete("${p.baseUrl}/api/channels/po-frontend/share") }
            p.switchActive("projb")
            assertEquals(setOf("po-backend"), p.channelIds(E2ePlatform.agentToken("backend")), "revoke → shared channel immediately gone")
        }
    }

    @Test
    fun noEntryEgress_and_secretNeedleAbsent_inGranteeScope() = runBlocking {
        platform().use { p ->
            p.provisionShareAndSwitch()
            p.asOperator().use { c ->
                val acl = c.get("${p.baseUrl}/api/acl").body<List<AclEntry>>()
                assertTrue(acl.all { it.projectId == "projb" }, "no foreign (proja) AclEntry egresses into projb scope")
                val keyText = c.get("${p.baseUrl}/api/config/apikey").bodyAsText()
                assertNoNeedles("projb config apikey", keyText) // SECRET seeded as projb key → masked only
                assertEquals("***0000", CommJson.decodeFromString<ApiKeyView>(keyText).masked)
            }
        }
    }
}
