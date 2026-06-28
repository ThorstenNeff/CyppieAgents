package com.tneff.cyppieagents.settings

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.ApiKeyRequest
import com.tneff.cyppieagents.model.ApiKeyView
import com.tneff.cyppieagents.model.RepoConfigRequest
import com.tneff.cyppieagents.model.RepoConfigView
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-85 stub→real swap — e2e proof that [ConfigHttpRepository] talks the CYP-96 config endpoints
 * correctly against a **real embedded Ktor server** (not a fake), so the swap from [StubConfigRepository]
 * is only a constructor change. Covers the round-trip, the server's reason-code mapping, and — the
 * reviewer's key invariant — the API key stays **write-only**: the server receives the plaintext, the
 * client only ever decodes `{set, masked}`.
 */
class ConfigHttpRepositoryE2eTest {

    @Test
    fun roundTrips_configEndpoints_mapsErrors_andKeyStaysWriteOnly() = runBlocking {
        var storedRepo = RepoConfigView(configured = false)
        var storedKey = ApiKeyView(set = false, masked = null)
        var receivedPlaintextKey: String? = null

        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/config/repo") {
                    call.respondText(CommJson.encodeToString(RepoConfigView.serializer(), storedRepo), ContentType.Application.Json)
                }
                put("/api/config/repo") {
                    val req = CommJson.decodeFromString(RepoConfigRequest.serializer(), call.receiveText())
                    if (req.url.isBlank()) {
                        call.respondText(
                            CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError("invalid_repo_url", "bad url"))),
                            ContentType.Application.Json, HttpStatusCode.BadRequest,
                        )
                    } else {
                        storedRepo = RepoConfigView(configured = true, url = req.url, branch = req.branch)
                        call.respondText(CommJson.encodeToString(RepoConfigView.serializer(), storedRepo), ContentType.Application.Json)
                    }
                }
                get("/api/config/apikey") {
                    call.respondText(CommJson.encodeToString(ApiKeyView.serializer(), storedKey), ContentType.Application.Json)
                }
                put("/api/config/apikey") {
                    val req = CommJson.decodeFromString(ApiKeyRequest.serializer(), call.receiveText())
                    receivedPlaintextKey = req.apiKey // the server (only) sees the plaintext
                    storedKey = ApiKeyView(set = true, masked = "***" + req.apiKey.takeLast(4))
                    call.respondText(CommJson.encodeToString(ApiKeyView.serializer(), storedKey), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = ConfigHttpRepository(client, "http://127.0.0.1:$port", token = "op")

                // Repo: unset → NotConfigured; PUT → Configured (round-trips the :core wire DTOs).
                assertIs<RepoConfigState.NotConfigured>(repo.getRepo())
                val saved = repo.putRepo("git@github.com:o/r.git", "dev")
                assertEquals("git@github.com:o/r.git", saved.url)
                assertEquals("dev", saved.branch)
                assertIs<RepoConfigState.Configured>(repo.getRepo())

                // Server 400 → mapped to the reason code the VM keys on.
                val err = assertFails { repo.putRepo("", "main") }
                assertIs<ConfigException>(err)
                assertEquals("invalid_repo_url", err.code)

                // API key: write-only. PUT sends the plaintext; GET/PUT responses carry only `masked`.
                assertEquals(ApiKeyState(set = false, masked = null), repo.getApiKey())
                val putState = repo.putApiKey("sk-ant-supersecret1234")
                assertEquals(true, putState.set)
                assertEquals("***1234", putState.masked)
                assertEquals("sk-ant-supersecret1234", receivedPlaintextKey) // server got the plaintext
                val getState = repo.getApiKey()
                assertEquals("***1234", getState.masked)
                assertTrue(getState.masked?.contains("supersecret") != true, "the plaintext key never round-trips back")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
