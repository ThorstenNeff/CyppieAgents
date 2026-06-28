package com.tneff.cyppieagents

import com.tneff.cyppieagents.boot.ProjectConfigStore
import com.tneff.cyppieagents.boot.ProjectDeleter
import com.tneff.cyppieagents.boot.ProjectRegistry
import com.tneff.cyppieagents.boot.RepoConfig
import com.tneff.cyppieagents.boot.Secrets
import com.tneff.cyppieagents.boot.WorktreeManager
import com.tneff.cyppieagents.comm.Hub
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.comm.InMemoryMessageStore
import com.tneff.cyppieagents.events.InMemoryEventSink
import com.tneff.cyppieagents.events.SystemTimeSource
import com.tneff.cyppieagents.model.AclEntry
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Channel
import com.tneff.cyppieagents.model.ChannelKind
import com.tneff.cyppieagents.model.CreateProjectRequest
import com.tneff.cyppieagents.model.SwitchActiveRequest
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.commRoutes
import com.tneff.cyppieagents.routing.projectRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * S13 / CYP-102 — the end-to-end wiring: `POST /api/projects/switch` flips the registry pointer AND
 * re-scopes the live comm hub (`onActiveSwitch = state::rescope`), so the comm read-paths follow the
 * active project WITHOUT a restart. Proves no-cross-project at the API (only the active project's
 * channels/ACL egress) and switch-changes-view live.
 */
class ProjectSwitchRescopeTest {

    private val op = HubState.OPERATOR_ID
    private val tokens = TokenRegistry(mapOf("tok-fe" to "frontend"), operatorToken = "tok-op")

    private fun state(): HubState {
        val chA = Channel("ca", "ca", ChannelKind.GROUP, listOf(op), projectId = "alpha")
        val chB = Channel("cb", "cb", ChannelKind.GROUP, listOf(op), projectId = "beta")
        val entries = listOf(
            AclEntry("ca", op, canRead = true, canWrite = true, projectId = "alpha"),
            AclEntry("cb", op, canRead = true, canWrite = true, projectId = "beta"),
        )
        return HubState(emptyList(), listOf(chA, chB), entries, activeProjectId = "alpha", operatorId = op)
    }

    private fun registry(): ProjectRegistry =
        ProjectRegistry(file = null, seedProjectId = "alpha", seedProjectName = "Alpha").apply {
            create(CreateProjectRequest("beta", "Beta"))
        }

    private fun deleter(reg: ProjectRegistry): ProjectDeleter {
        val gitRoot = Files.createTempDirectory("switch-rescope").toFile()
        val config = ProjectConfigStore(null, RepoConfig("u", "main"), Secrets(mapOf("t" to "po"), "op", apiKey = null))
        return ProjectDeleter(reg, config, InMemoryEventSink(SystemTimeSource()), WorktreeManager(FakeGit(), gitRoot))
    }

    private fun ApplicationTestBuilder.app(s: HubState, reg: ProjectRegistry) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(WebSockets)
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            val hub = Hub(s, InMemoryMessageStore())
            routing {
                commRoutes(hub, s, tokens)
                projectRoutes(reg, deleter(reg), tokens, onActiveSwitch = s::rescope) // the CYP-102 wiring
            }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    @Test
    fun switch_rescopesChannelsAndAcl_withoutRestart() = runBlocking {
        testApplication {
            val s = state(); val reg = registry(); app(s, reg)
            val c = jsonClient()

            // active = alpha → only alpha's channel + ACL entry visible (no beta leak)
            val chAlpha: List<Channel> = c.get("/api/channels") { bearerAuth("tok-op") }.body()
            assertEquals(listOf("ca"), chAlpha.map { it.id }, "active=alpha → only alpha channel")
            val aclAlpha: List<AclEntry> = c.get("/api/acl") { bearerAuth("tok-op") }.body()
            assertEquals(listOf("ca"), aclAlpha.map { it.channelId }, "active=alpha → only alpha ACL entry")

            // switch to beta — the same wiring production uses
            val sw = c.post("/api/projects/switch") {
                bearerAuth("tok-op"); contentType(ContentType.Application.Json); setBody(SwitchActiveRequest("beta"))
            }
            assertEquals(HttpStatusCode.OK, sw.status)

            // view changed live: now only beta's channel + ACL entry, alpha gone
            val chBeta: List<Channel> = c.get("/api/channels") { bearerAuth("tok-op") }.body()
            assertEquals(listOf("cb"), chBeta.map { it.id }, "switch changed /api/channels without restart")
            val aclBeta: List<AclEntry> = c.get("/api/acl") { bearerAuth("tok-op") }.body()
            assertEquals(listOf("cb"), aclBeta.map { it.channelId }, "switch changed /api/acl without restart")
            assertTrue(chBeta.none { it.id == "ca" }, "no-cross-project: alpha channel not leaked after switch")
        }
    }
}
