package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigRequest
import com.tneff.cyppieagents.model.RepoConfigView
import com.tneff.cyppieagents.model.Role
import io.ktor.client.call.body
import io.ktor.client.request.get
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
 * E2E Journey J5 (CYP-109) — Settings (repo + API key) per project, over the REAL platform. GET is
 * participant-readable (masked status); PUT is operator-gated/fail-closed; config follows the active
 * switch (CYP-103); the **CYP-104** plausibility + redaction floor is enforced end-to-end; the raw key
 * NEVER egresses (only `***<last4>`), guarded by the raw-byte needle helper.
 */
class J5SettingsE2eTest {

    private fun twoProjects() = e2ePlatform(
        listOf(
            SeedProject("alpha", "A", listOf(SeedAgent("po", Role.PO), SeedAgent("frontend"))),
            SeedProject("beta", "B", listOf(SeedAgent("backend")), apiKey = "beta-secret-key-1234"),
        ),
    )

    @Test
    fun apikey_getParticipant_putOperator_maskedOnly_rawNeverOnWire() = runBlocking {
        twoProjects().use { p ->
            p.asAgent("frontend").use { a ->
                assertEquals(HttpStatusCode.OK, a.get("${p.baseUrl}/api/config/apikey").status, "participant may read the masked status")
                assertEquals(
                    HttpStatusCode.Forbidden,
                    a.put("${p.baseUrl}/api/config/apikey") { contentType(ContentType.Application.Json); setBody(ApiKeyRequest("synthetic-key-000000")) }.status,
                    "participant cannot set the key",
                )
            }
            p.asOperator().use { c ->
                val v = c.put("${p.baseUrl}/api/config/apikey") {
                    contentType(ContentType.Application.Json); setBody(ApiKeyRequest("synthetic-key-12345678ABCD"))
                }.body<ApiKeyView>()
                assertTrue(v.set); assertEquals("***ABCD", v.masked, "masked to ***<last4>")
                // the raw key NEVER appears on the wire (only the masked tail)
                val keyText = c.get("${p.baseUrl}/api/config/apikey").bodyAsText()
                assertNoNeedles("config apikey", keyText, secrets = setOf("synthetic-key-12345678ABCD"))
            }
        }
    }

    @Test
    fun apikey_cyp104_rejectsShortAndWhitespace_acceptsLong() = runBlocking {
        twoProjects().use { p ->
            p.asOperator().use { c ->
                for (bad in listOf("short", "has space inside here", "   ")) {
                    val r = c.put("${p.baseUrl}/api/config/apikey") {
                        contentType(ContentType.Application.Json); setBody(ApiKeyRequest(bad))
                    }
                    assertEquals(HttpStatusCode.BadRequest, r.status, "CYP-104: implausible key '$bad' rejected")
                    assertEquals("invalid_api_key", r.body<ApiErrorBody>().error.code)
                }
                val ok = c.put("${p.baseUrl}/api/config/apikey") {
                    contentType(ContentType.Application.Json); setBody(ApiKeyRequest("plausible-key-9999WXYZ"))
                }
                assertEquals(HttpStatusCode.OK, ok.status, "a plausible long key is accepted (prefix-tolerant)")
                assertEquals("***WXYZ", ok.body<ApiKeyView>().masked)
            }
        }
    }

    @Test
    fun apikey_followsSwitch_perProject_AuntouchedByB() = runBlocking {
        twoProjects().use { p ->
            p.asOperator().use { c ->
                c.put("${p.baseUrl}/api/config/apikey") { contentType(ContentType.Application.Json); setBody(ApiKeyRequest("alpha-secret-key-AAAA")) }
            }
            assertEquals("***AAAA", p.maskedKey())
            p.switchActive("beta")
            assertEquals("***1234", p.maskedKey(), "config follows the switch → beta's seeded key")
            p.asOperator().use { c ->
                c.put("${p.baseUrl}/api/config/apikey") { contentType(ContentType.Application.Json); setBody(ApiKeyRequest("beta-secret-key-BBBB")) }
            }
            assertEquals("***BBBB", p.maskedKey())
            p.switchActive("alpha")
            assertEquals("***AAAA", p.maskedKey(), "alpha's key untouched by beta-context writes (CYP-103 class)")
        }
    }

    @Test
    fun repo_putOperator_getParticipant_invalidUrl400() = runBlocking {
        twoProjects().use { p ->
            p.asAgent("frontend").use { a ->
                assertEquals(HttpStatusCode.OK, a.get("${p.baseUrl}/api/config/repo").status, "participant may read repo config")
                assertEquals(
                    HttpStatusCode.Forbidden,
                    a.put("${p.baseUrl}/api/config/repo") { contentType(ContentType.Application.Json); setBody(RepoConfigRequest("git@github.com:o/r.git", "main")) }.status,
                    "participant cannot set repo",
                )
            }
            p.asOperator().use { c ->
                val r = c.put("${p.baseUrl}/api/config/repo") {
                    contentType(ContentType.Application.Json); setBody(RepoConfigRequest("not-a-valid-repo-url", "main"))
                }
                assertEquals(HttpStatusCode.BadRequest, r.status)
                assertEquals("invalid_repo_url", r.body<ApiErrorBody>().error.code)

                val ok = c.put("${p.baseUrl}/api/config/repo") {
                    contentType(ContentType.Application.Json); setBody(RepoConfigRequest("git@github.com:org/repo.git", "develop"))
                }.body<RepoConfigView>()
                assertTrue(ok.configured); assertEquals("develop", ok.branch)
            }
        }
    }

    private suspend fun E2ePlatform.maskedKey(): String? =
        asOperator().use { it.get("$baseUrl/api/config/apikey").body<ApiKeyView>().masked }
}
