package com.tneff.cyppieagents

import com.tneff.cyppieagents.avatar.AvatarBlobStore
import com.tneff.cyppieagents.avatar.AvatarPresetResolver
import com.tneff.cyppieagents.boot.AgentConfig
import com.tneff.cyppieagents.boot.AgentConfigRegistry
import com.tneff.cyppieagents.boot.AgentManagement
import com.tneff.cyppieagents.boot.LifecycleManager
import com.tneff.cyppieagents.comm.HubState
import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.connector.ConnectorSessions
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentAvatar
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.agentMgmtRoutes
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-215 — the avatar HTTP contract end-to-end over the REAL routes: operator-gated multipart upload →
 * validate/re-encode/store → participant-gated serve of the 256² PNG; a hostile upload → uniform 400; a
 * missing avatar → 404 (client default); the operator gate on the write; and the preset-set edit path with
 * its fail-closed style allow-list.
 */
class AvatarRoutesTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private fun mgmt(): AgentManagement {
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")),
            HubState.OPERATOR_ID, "default",
        )
        val configs = AgentConfigRegistry(
            listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("frontend", "FE", Role.WORKER, launch = "claude")),
        )
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("po" to "po", "frontend" to "frontend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { id, _ -> FakeSession(id) },
        )
        val blobs = AvatarBlobStore(Files.createTempDirectory("avatar-route").toFile())
        val presets = AvatarPresetResolver(Files.createTempDirectory("avatar-route-presets").toFile())
        return AgentManagement(
            state, lifecycle, configs, ensureWorktree = {}, deleteWorktree = {},
            avatarBlobs = blobs, avatarPresets = presets,
        )
    }

    private fun ApplicationTestBuilder.app(mgmt: AgentManagement) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message)))
                }
            }
            routing { agentMgmtRoutes(mgmt, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op")) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private fun realPng(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); g.fillRect(0, 0, w, h); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray()
    }

    private fun filePart(bytes: ByteArray, name: String) = MultiPartFormDataContent(formData {
        append("file", bytes, Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
            append(HttpHeaders.ContentType, "image/png")
        })
    })

    @Test fun upload_operator_thenServe_participant_roundTrips() = testApplication {
        app(mgmt()); val c = jsonClient()
        // operator uploads a 400x200 PNG
        val up = c.post("/api/agents/frontend/avatar") { bearerAuth("tok-op"); setBody(filePart(realPng(400, 200), "me.png")) }
        assertEquals(HttpStatusCode.OK, up.status)
        val detail = up.body<AgentDetail>()
        assertTrue(detail.avatar is AgentAvatar.Upload, "after upload the avatar is an Upload(ref): ${detail.avatar}")
        // a participant serves it → 256x256 PNG bytes
        val served = c.get("/api/agents/frontend/avatar") { bearerAuth("tok-fe") }
        assertEquals(HttpStatusCode.OK, served.status)
        val decoded = ImageIO.read(ByteArrayInputStream(served.readRawBytes()))
        assertEquals(256, decoded.width); assertEquals(256, decoded.height)
    }

    @Test fun upload_svg_isRejected_uniform400() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.post("/api/agents/frontend/avatar") { bearerAuth("tok-op"); setBody(filePart("<svg/>".toByteArray(), "x.png")) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertEquals("avatar_rejected", r.body<ApiErrorBody>().error.code) // uniform (no parser-mapping leak)
    }

    @Test fun upload_participant_isForbidden() = testApplication {
        app(mgmt()); val c = jsonClient()
        val r = c.post("/api/agents/frontend/avatar") { bearerAuth("tok-fe"); setBody(filePart(realPng(64, 64), "x.png")) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test fun serve_noAvatar_is404() = testApplication {
        app(mgmt()); val c = jsonClient()
        assertEquals(HttpStatusCode.NotFound, c.get("/api/agents/po/avatar") { bearerAuth("tok-fe") }.status)
    }

    @Test fun setPreset_viaEdit_persists_andUnknownStyle_is400() = testApplication {
        app(mgmt()); val c = jsonClient()
        val ok = c.put("/api/agents/frontend") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AgentEdit(role = Role.WORKER, avatar = AgentAvatar.Preset("bottts", "seed-1")))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val d = c.get("/api/agents/frontend") { bearerAuth("tok-fe") }.body<AgentDetail>()
        assertEquals(AgentAvatar.Preset("bottts", "seed-1"), d.avatar, "the preset persisted onto the agent")

        val bad = c.put("/api/agents/frontend") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AgentEdit(role = Role.WORKER, avatar = AgentAvatar.Preset("h4x-style", "s")))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("avatar_style_not_allowed", bad.body<ApiErrorBody>().error.code) // fail-closed allow-list
    }
}
