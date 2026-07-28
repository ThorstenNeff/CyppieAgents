package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.BootOrchestrator
import com.tneff.cyppieagents.boot.BootedPlatform
import com.tneff.cyppieagents.boot.PlatformConfig
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ProcessSpawner
import com.tneff.cyppieagents.model.DeliveredMessage
import com.tneff.cyppieagents.model.EditMessageRequest
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * CYP-905 — HTTP route-teeth for PUT /api/channels/{id}/messages/{msgId} over the REAL wired platform: the
 * author edits own → 200 with editedAt set; a non-author (operator, a writer but not the author) → 403; a missing
 * message → 404. The exception→status mapping is what [com.tneff.cyppieagents.contract.RestContract] declares;
 * the SEND chokepoint (postAsAgent) is untouched — this is the parallel guarded EDIT path.
 */
class Cyp905EditRouteTest {
    private class SilentProcess : AgentProcess {
        override val stdoutLines: Flow<String> = emptyFlow()
        override suspend fun writeLine(line: String) {}
        override fun destroy() {}
    }

    private fun bootFake(): BootedPlatform {
        val config = PlatformConfig(
            RepoConfig("git@github.com:org/repo.git", "main"),
            agents = listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("backend", "BE", Role.WORKER)),
        )
        val secrets = Secrets(mapOf("tok-backend" to "backend"), operatorToken = "tok-op", apiKey = null)
        val gitRoot = Files.createTempDirectory("cyp905-route-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> SilentProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json() } }
    private fun io.ktor.client.request.HttpRequestBuilder.op() = header(HttpHeaders.Authorization, "Bearer tok-op")
    private fun io.ktor.client.request.HttpRequestBuilder.agent() = header(HttpHeaders.Authorization, "Bearer tok-backend")

    @Test
    fun editRoute_authorOk_nonAuthorForbidden_missingNotFound() = testApplication {
        val booted = bootFake()
        application { installPlatform(booted) }
        val c = jsonClient()
        val posted = booted.hub.postAsAgent("backend", "po-backend", "original")

        // author (backend) edits own → 200 + editedAt set + body changed
        val ok = c.put("/api/channels/po-backend/messages/${posted.id}") {
            agent(); contentType(ContentType.Application.Json); setBody(EditMessageRequest("corrected"))
        }
        assertEquals(HttpStatusCode.OK, ok.status, "author edit is 200")
        val body = ok.body<DeliveredMessage>()
        assertEquals("corrected", body.message.body, "body replaced")
        assertNotNull(body.editedAt, "response carries the editedAt marker")

        // non-author (operator is a writer in the spoke but not the author) → 403
        assertEquals(
            HttpStatusCode.Forbidden,
            c.put("/api/channels/po-backend/messages/${posted.id}") {
                op(); contentType(ContentType.Application.Json); setBody(EditMessageRequest("hijack"))
            }.status,
            "a non-author writer is 403",
        )

        // missing message → 404
        assertEquals(
            HttpStatusCode.NotFound,
            c.put("/api/channels/po-backend/messages/does-not-exist") {
                agent(); contentType(ContentType.Application.Json); setBody(EditMessageRequest("x"))
            }.status,
            "editing a missing message is 404",
        )
    }
}
