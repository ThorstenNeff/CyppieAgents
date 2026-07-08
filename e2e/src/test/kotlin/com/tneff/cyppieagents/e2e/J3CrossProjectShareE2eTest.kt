package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.AuthorizeShareRequest
import com.tneff.cyppieagents.model.ChannelShareView
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.SwitchActiveRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E2E Journey J3 (CYP-108) — cross-project channel sharing (owner consent), over the REAL platform.
 *
 * Owner project `proja` (active boot) owns spokes `po-frontend` + `po-frontend2`; grantee project `projb`
 * owns `po-backend`. The share is the owner-authorized, per-channel gate.
 *
 * **Coverage here = the share-authorization surface that is E2E-constructible on `c278727`:** lifecycle
 * (authorize/disclose/revoke), operator-gating, **anti-injection** (only the operator authorizes — an
 * agent token changes neither share nor switch nor config), unknown-channel 404, **no-over-widen at the
 * record level** (the owner's neighbor channel is NOT auto-shared), **no foreign AclEntry egress** into
 * the grantee scope, and **secret-needle absence** (a seeded key egresses masked only).
 *
 * **Grantee-READ axes (UNBLOCKED — CYP-317 re-verify):** these need a cross-project channel-membership,
 * provisioned via `PUT /api/acl` (the entry seam that atomically syncs `channel.members`, CYP-112). The
 * earlier blocker — the PO-lockout guard 409ing on an out-of-active-scope hub channel — was fixed in CYP-111
 * (`poHubChannelIds()` is now active-project-scoped), so `PUT /api/acl` IS usable in a multi-project state.
 * The same-project non-member grant + enforcement is now proven end-to-end in Cyp317NonMemberGrantE2eTest;
 * the cross-project grantee-READ axes (member sees / neighbor NOT / revoke→gone) can be added here as a J3
 * follow-up. The permit DECISION itself stays unit-proven in AclMatrixSharePermitTest / ChannelSharePermitWiringTest.
 */
class J3CrossProjectShareE2eTest {

    private fun platform() = e2ePlatform(
        listOf(
            SeedProject("proja", "Proj A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"), SeedAgent("frontend2"))),
            SeedProject("projb", "Proj B", listOf(SeedAgent("backend")), apiKey = Needles.SECRET),
        ),
    )

    @Test
    fun share_authorize_disclose_revoke_operator() = runBlocking {
        platform().use { p ->
            p.asOperator().use { c ->
                assertFalse(c.get("${p.baseUrl}/api/channels/po-frontend/share").body<ChannelShareView>().shared, "default: not shared")

                val shared = c.put("${p.baseUrl}/api/channels/po-frontend/share") {
                    contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("projb")))
                }.body<ChannelShareView>()
                assertTrue(shared.shared); assertTrue(shared.sharedAt != null)
                assertTrue(c.get("${p.baseUrl}/api/channels/po-frontend/share").body<ChannelShareView>().shared)

                assertFalse(c.delete("${p.baseUrl}/api/channels/po-frontend/share").body<ChannelShareView>().shared, "revoke → gate closed")
            }
        }
    }

    @Test
    fun share_unknownChannel_404() = runBlocking {
        platform().use { p ->
            p.asOperator().use { c ->
                val r = c.put("${p.baseUrl}/api/channels/ghost/share") {
                    contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("projb")))
                }
                assertEquals(HttpStatusCode.NotFound, r.status)
                assertEquals("channel_not_found", r.body<ApiErrorBody>().error.code)
            }
        }
    }

    @Test
    fun share_noOverWiden_recordLevel_neighborNotShared() = runBlocking {
        platform().use { p ->
            p.asOperator().use { c ->
                c.put("${p.baseUrl}/api/channels/po-frontend/share") {
                    contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("projb")))
                }
                // the neighbor channel of the SAME owner is NOT auto-shared (per-channel gate) — exact false
                assertEquals(false, c.get("${p.baseUrl}/api/channels/po-frontend2/share").body<ChannelShareView>().shared)
            }
        }
    }

    @Test
    fun antiInjection_agentToken_cannotShareSwitchOrConfig_allFailClosed() = runBlocking {
        platform().use { p ->
            p.asAgent("frontend").use { a ->
                assertEquals(HttpStatusCode.Forbidden, a.put("${p.baseUrl}/api/channels/po-frontend/share") {
                    contentType(ContentType.Application.Json); setBody(AuthorizeShareRequest(setOf("projb")))
                }.status, "agent cannot authorize a share")
                assertEquals(HttpStatusCode.Forbidden, a.delete("${p.baseUrl}/api/channels/po-frontend/share").status, "agent cannot revoke a share")
                assertEquals(HttpStatusCode.Forbidden, a.post("${p.baseUrl}/api/projects/switch") {
                    contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("projb"))
                }.status, "agent cannot switch the active project")
                assertEquals(HttpStatusCode.Forbidden, a.put("${p.baseUrl}/api/config/apikey") {
                    contentType(ContentType.Application.Json); setBody(ApiKeyRequest("synthetic-key-000000"))
                }.status, "agent cannot change config")
            }
            // and nothing actually changed: po-frontend still not shared
            assertFalse(p.asOperator().use { it.get("${p.baseUrl}/api/channels/po-frontend/share").body<ChannelShareView>().shared })
        }
    }

    @Test
    fun noEntryEgress_and_secretNeedleAbsent_inGranteeScope() = runBlocking {
        platform().use { p ->
            p.switchActive("projb")
            p.asOperator().use { c ->
                // no foreign (proja) AclEntry egresses into projb scope (raw-byte + structural)
                val aclText = c.get("${p.baseUrl}/api/acl").assertNoNeedles("projb /api/acl", foreignProjectIds = setOf("\"proja\""))
                assertTrue(CommJson.decodeFromString<List<AclEntry>>(aclText).all { it.projectId == "projb" }, "only projb-stamped entries in projb scope")
                // the seeded projb apiKey egresses MASKED only — the raw secret never on the wire
                val keyText = c.get("${p.baseUrl}/api/config/apikey").assertNoNeedles("projb /api/config/apikey")
                assertEquals("***0000", CommJson.decodeFromString<ApiKeyView>(keyText).masked)
            }
        }
    }
}
