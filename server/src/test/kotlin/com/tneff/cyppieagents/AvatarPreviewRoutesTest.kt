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
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.auth.FakeIdentityProvider
import com.tneff.cyppieagents.auth.InMemoryRoleStore
import com.tneff.cyppieagents.auth.ResolvedIdentity
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.agentMgmtRoutes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-219 — the DiceBear preset PREVIEW endpoint contract + Reviewer-security teeth. Read-only, participant-
 * gated, **egress-free** (server resolves the self-hosted bundled asset; the client NEVER calls
 * api.dicebear.com — this same-origin route is the only source). Reject-matrix is the security core:
 * unknown/traversal-y style → 404 (allow-list, never a path), no bundled asset → 404 (graceful degrade).
 */
class AvatarPreviewRoutesTest {

    private class FakeSession(override val agentId: String) : ConnectorSession {
        override val events: Flow<StreamJsonEvent> = emptyFlow()
        override suspend fun sendTurn(turn: UserTurn) {}
        override fun close() {}
        override suspend fun closeAndAwait() {}
    }

    private fun mgmt(presetsRoot: File): AgentManagement {
        val state = HubState.hubAndSpoke(
            listOf(Agent("po", "PO", Role.PO, "po"), Agent("frontend", "FE", Role.WORKER, "frontend")),
            HubState.OPERATOR_ID, "default",
        )
        val configs = AgentConfigRegistry(listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("frontend", "FE", Role.WORKER, launch = "claude")))
        val lifecycle = LifecycleManager(mapOf("po" to "po", "frontend" to "frontend"), ConnectorSessions(), ensureWorktree = {}, spawn = { id, _ -> FakeSession(id) })
        return AgentManagement(
            state, lifecycle, configs, ensureWorktree = {}, deleteWorktree = {},
            avatarBlobs = AvatarBlobStore(Files.createTempDirectory("preview-blobs").toFile()),
            avatarPresets = AvatarPresetResolver(presetsRoot),
        )
    }

    private fun ApplicationTestBuilder.app(mgmt: AgentManagement) {
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message))) }
            }
            routing { agentMgmtRoutes(mgmt, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op", loopbackPosture = true)) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ClientContentNegotiation) { json(CommJson) } }

    private fun bundle(root: File, style: String, n: Int, tint: Color): ByteArray {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); g.color = tint; g.fillRect(0, 0, 256, 256); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out); val b = out.toByteArray()
        File(root, style).mkdirs(); File(root, "$style/$style-$n.png").writeBytes(b); return b
    }

    @Test fun cyp232_verifiedSessionServesAvatarRoutes_noAuthStill401() = testApplication {
        // CYP-232: the avatar serve + preview were token-only (requireParticipant) → the tokenless public SPA
        // (verified Kratos session, no agent token) got 401 → fallback icon / placeholder grid. The read-tier gate
        // (requireCommReader) now admits a verified human session too. A MEMBER session suffices (read-tier).
        val presets = Files.createTempDirectory("preview-cyp232").toFile()
        bundle(presets, "bottts", 0, Color(20, 140, 90))
        val registry = TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op", loopbackPosture = true)
        val deps = AuthDeps(registry, FakeIdentityProvider(mapOf("sess-m" to ResolvedIdentity("mem-1", verified = true))), InMemoryRoleStore(), { 1L })
        application {
            install(ContentNegotiation) { json(CommJson) }
            install(StatusPages) {
                exception<ApiException> { call, cause -> call.respond(cause.status, ApiErrorBody(com.tneff.cyppieagents.model.ApiError(cause.code, cause.message))) }
            }
            routing { agentMgmtRoutes(mgmt(presets), registry, deps) }
        }
        // preview: a verified session renders the PNG (was 401 before the read-tier gate).
        assertEquals(
            HttpStatusCode.OK,
            jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=x") { header("X-Session-Token", "sess-m") }.status,
            "verified session must serve the preset preview PNG",
        )
        // serve /{id}/avatar: the session passes the gate (404 = no avatar set, NOT 401 → auth admitted).
        assertEquals(
            HttpStatusCode.NotFound,
            jsonClient().get("/api/agents/frontend/avatar") { header("X-Session-Token", "sess-m") }.status,
            "verified session passes the avatar-serve gate (404 no-avatar, not 401)",
        )
        // no auth at all → still 401 on both (fail-closed unchanged).
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=x").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/agents/frontend/avatar").status)
    }

    @Test fun preview_bundledStyle_servesPng_withCacheHeaders() = testApplication {
        val presets = Files.createTempDirectory("preview").toFile()
        val asset = bundle(presets, "bottts", 0, Color(20, 140, 90))
        app(mgmt(presets))
        val r = jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=x") { bearerAuth("tok-fe") }
        assertEquals(HttpStatusCode.OK, r.status)
        assertContentEquals(asset, r.readRawBytes(), "serves the exact self-hosted bundled PNG (egress-free)")
        val decoded = ImageIO.read(ByteArrayInputStream(r.readRawBytes())); assertEquals(256, decoded.width)
        assertNotNull(r.headers[HttpHeaders.ETag], "deterministic ETag set")
        assertTrue(r.headers[HttpHeaders.CacheControl]?.contains("max-age") == true, "cacheable for the grid")
    }

    @Test fun preview_unknownStyle_404_degrade() = testApplication {
        app(mgmt(Files.createTempDirectory("preview-unknown").toFile()))
        // a style NOT in the allow-list → resolver rejects (never touches a path) → 404
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/agents/frontend/avatar/preview?style=hacker-style&seed=x") { bearerAuth("tok-fe") }.status)
    }

    @Test fun preview_traversalStyle_404_neverAPath() = testApplication {
        app(mgmt(Files.createTempDirectory("preview-trav").toFile()))
        for (bad in listOf("../../etc/passwd", "..", "bottts/../avataaars", "")) {
            assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/agents/frontend/avatar/preview?style=$bad&seed=x") { bearerAuth("tok-fe") }.status, "traversal/blank style '$bad' → 404 (allow-list, not a path)")
        }
    }

    @Test fun preview_nonAllowlistedStyle_evenWithAssetPresent_still404() = testApplication {
        // NON-VACUOUS allow-list tooth: bundle a PNG under a style that is NOT license-cleared (`lorelei`).
        // The asset EXISTS on disk, so a 404 here can ONLY come from the allow-list gate — not a missing file.
        // (Break `isAllowed` → this serves the asset = 200 = RED. Distinguishes the gate from mere absence.)
        val presets = Files.createTempDirectory("preview-nonallow").toFile()
        bundle(presets, "lorelei", 0, Color(200, 50, 50))
        assertTrue("lorelei" !in AvatarPresetResolver.ALLOWED_STYLES, "precondition: lorelei is not allow-listed")
        app(mgmt(presets))
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/agents/frontend/avatar/preview?style=lorelei&seed=x") { bearerAuth("tok-fe") }.status, "non-allow-listed style → 404 even though its asset is bundled")
    }

    @Test fun preview_allowedStyleButNoAsset_404_degrade() = testApplication {
        app(mgmt(Files.createTempDirectory("preview-empty").toFile())) // allow-listed style, but nothing bundled
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=x") { bearerAuth("tok-fe") }.status)
    }

    @Test fun preview_deterministic_sameStyleSeed_sameBytes() = testApplication {
        val presets = Files.createTempDirectory("preview-det").toFile()
        repeat(8) { bundle(presets, "bottts", it, Color(10 + it * 20, 40, 200 - it * 10)) }
        app(mgmt(presets))
        val a = jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=stable") { bearerAuth("tok-fe") }.readRawBytes()
        val b = jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=stable") { bearerAuth("tok-fe") }.readRawBytes()
        assertContentEquals(a, b, "same (style, seed) → same preview bytes (deterministic index)")
    }

    @Test fun preview_noToken_401_participantGated() = testApplication {
        app(mgmt(Files.createTempDirectory("preview-auth").toFile()))
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/agents/frontend/avatar/preview?style=bottts&seed=x").status, "no token → 401 (participant-gated)")
    }
}
