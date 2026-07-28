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
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.ChannelMemberGrant
import com.tneff.cyppieagents.model.CreateChannelRequest
import com.tneff.cyppieagents.model.RenameChannelRequest
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.routing.installPlatform
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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

/**
 * CYP-878 (OS-F hardening) — HTTP route-teeth for the CYP-869/870 channel routes over the REAL wired platform,
 * proving the exception → status mapping through StatusPages is what the [com.tneff.cyppieagents.contract.RestContract]
 * declares. Non-vacuous: each asserts the real status code off a real request. The write chokepoint is untouched —
 * these exercise the OPERATOR topology-control routes + the participant read filters, not a new send path.
 */
class Cyp878ChannelRouteTest {
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
        val gitRoot = Files.createTempDirectory("cyp878-route-test").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val spawner = ProcessSpawner { _, _, _ -> SilentProcess() }
        return BootOrchestrator(config, secrets, WorktreeManager(FakeGit(), gitRoot), spawner, scope).boot()
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json() } }
    private fun io.ktor.client.request.HttpRequestBuilder.op() = header(HttpHeaders.Authorization, "Bearer tok-op")
    private fun io.ktor.client.request.HttpRequestBuilder.agent() = header(HttpHeaders.Authorization, "Bearer tok-backend")

    @Test
    fun channelRoutes_errorMapping() = testApplication {
        application { installPlatform(bootFake()) }
        val c = jsonClient()

        // POST /api/channels — reserved id → 400 channel_id_reserved (CYP-878)
        assertEquals(
            HttpStatusCode.BadRequest,
            c.post("/api/channels") { op(); contentType(ContentType.Application.Json); setBody(CreateChannelRequest("po-x", "Hijack", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", true, true)))) }.status,
            "a reserved po-* id is rejected 400",
        )
        // POST — HUB kind → 400 channel_kind_forbidden (CYP-869)
        assertEquals(
            HttpStatusCode.BadRequest,
            c.post("/api/channels") { op(); contentType(ContentType.Application.Json); setBody(CreateChannelRequest("grpA", "A", ChannelKind.HUB, emptyList())) }.status,
            "a HUB kind is rejected 400",
        )
        // POST — valid GROUP → 201
        assertEquals(
            HttpStatusCode.Created,
            c.post("/api/channels") { op(); contentType(ContentType.Application.Json); setBody(CreateChannelRequest("grpB", "B", ChannelKind.GROUP, listOf(ChannelMemberGrant("backend", true, true)))) }.status,
            "a valid GROUP channel is created 201",
        )
        // POST — duplicate id → 409 channel_exists
        assertEquals(
            HttpStatusCode.Conflict,
            c.post("/api/channels") { op(); contentType(ContentType.Application.Json); setBody(CreateChannelRequest("grpB", "B again", ChannelKind.GROUP, emptyList())) }.status,
            "a duplicate channel id is rejected 409",
        )
        // PUT /api/channels/{id} — unknown → 404 channel_not_found
        assertEquals(
            HttpStatusCode.NotFound,
            c.put("/api/channels/nope") { op(); contentType(ContentType.Application.Json); setBody(RenameChannelRequest("X")) }.status,
            "renaming an unknown channel is 404",
        )
        // DELETE /api/channels/{id} — a HUB spoke → 409 channel_hub_protected (CYP-869 protection)
        assertEquals(
            HttpStatusCode.Conflict,
            c.delete("/api/channels/po-backend") { op() }.status,
            "archiving a hub-and-spoke channel is rejected 409",
        )
        // GET /api/channels/{id}/messages?kind=invalid → 400 invalid_kind (CYP-870)
        assertEquals(
            HttpStatusCode.BadRequest,
            c.get("/api/channels/po-backend/messages?kind=BOGUS") { agent() }.status,
            "an invalid ?kind is rejected 400 (never a silent all)",
        )
    }

    @Test
    fun replyThreadRoute_returnsOk() = testApplication {
        val booted = bootFake()
        application { installPlatform(booted) }
        val c = jsonClient()
        // seed a root message via the real chokepoint, then the thread read returns 200 (participant read-tier).
        val root = booted.hub.postAsAgent("po", "po-backend", "root")
        assertEquals(
            HttpStatusCode.OK,
            c.get("/api/channels/po-backend/messages/${root.id}/thread") { agent() }.status,
            "GET …/messages/{msgId}/thread is a 200 participant read",
        )
    }
}
