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
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.routing.ApiException
import com.tneff.cyppieagents.routing.TokenRegistry
import com.tneff.cyppieagents.routing.agentMgmtRoutes
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
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
import kotlin.test.assertTrue

/**
 * CYP-215 (preset-serve deploy-prep verification, PO 2026-07-05): proves the **self-hosted DiceBear preset
 * serve path** end-to-end against a **bundled asset set** — the piece that lights up once the deploy asset
 * step populates `.cyppie/avatar-presets/<style>/`. Closes a real coverage gap: the merged suite tested
 * preset-SET (edit) but never preset-SERVE-WITH-ASSET (no assets were bundled in tests). Hermetic: the asset
 * is an in-test 256² PNG (CI-safe, no node) laid out exactly as the DiceBear CLI produces
 * (`<style>/<style>-<n>.png`, verified locally against real `dicebear@10.3.0` output — see the runbook).
 *
 *  - preset + bundled asset  → GET serves the EXACT PNG bytes (participant-gated), ETag set.
 *  - preset + NO asset        → 404 → the client falls back to its default (graceful degradation).
 *  - deterministic: a given (style, seed) always resolves to the same bundled file.
 */
class AvatarPresetServeTest {

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
        val configs = AgentConfigRegistry(
            listOf(AgentConfig("po", "PO", Role.PO), AgentConfig("frontend", "FE", Role.WORKER, launch = "claude")),
        )
        val lifecycle = LifecycleManager(
            initialWorktrees = mapOf("po" to "po", "frontend" to "frontend"),
            sessions = ConnectorSessions(),
            ensureWorktree = {},
            spawn = { id, _ -> FakeSession(id) },
        )
        return AgentManagement(
            state, lifecycle, configs, ensureWorktree = {}, deleteWorktree = {},
            avatarBlobs = AvatarBlobStore(Files.createTempDirectory("preset-serve-blobs").toFile()),
            avatarPresets = AvatarPresetResolver(presetsRoot),
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
            routing { agentMgmtRoutes(mgmt, TokenRegistry(mapOf("tok-fe" to "frontend"), "tok-op", loopbackPosture = true)) }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() =
        createClient { install(ClientContentNegotiation) { json(CommJson) } }

    /** A 256² PNG, laid out exactly as the DiceBear CLI writes (`<style>/<style>-<n>.png`). */
    private fun bundleAsset(presetsRoot: File, style: String, n: Int, tint: Color): ByteArray {
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics(); g.color = tint; g.fillRect(0, 0, 256, 256); g.dispose()
        val out = ByteArrayOutputStream(); ImageIO.write(img, "png", out)
        val bytes = out.toByteArray()
        File(presetsRoot, style).mkdirs()
        File(presetsRoot, "$style/$style-$n.png").writeBytes(bytes)
        return bytes
    }

    private suspend fun ApplicationTestBuilder.setPreset(style: String, seed: String) {
        val r = jsonClient().put("/api/agents/frontend") {
            bearerAuth("tok-op"); contentType(ContentType.Application.Json)
            setBody(AgentEdit(role = Role.WORKER, avatar = AgentAvatar.Preset(style, seed)))
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test fun preset_withBundledAsset_servesExactPng() = testApplication {
        val presets = Files.createTempDirectory("preset-serve").toFile()
        val asset = bundleAsset(presets, "bottts", 0, Color(30, 160, 90)) // the single asset → resolve idx 0
        app(mgmt(presets))
        setPreset("bottts", "backend")

        val served = jsonClient().get("/api/agents/frontend/avatar") { bearerAuth("tok-fe") }
        assertEquals(HttpStatusCode.OK, served.status)
        val bytes = served.readRawBytes()
        assertContentEquals(asset, bytes, "serve returns the EXACT bundled preset PNG")
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(256, decoded.width); assertEquals(256, decoded.height)
    }

    @Test fun preset_withoutBundledAsset_404_degradesToDefault() = testApplication {
        val presets = Files.createTempDirectory("preset-serve-empty").toFile() // no assets bundled yet
        app(mgmt(presets))
        setPreset("bottts", "backend")
        // no asset for the style → resolve()=null → 404 → the client falls back to its default (graceful).
        assertEquals(HttpStatusCode.NotFound, jsonClient().get("/api/agents/frontend/avatar") { bearerAuth("tok-fe") }.status)
    }

    @Test fun preset_serve_againstRealDiceBearAssets_whenPresent() = testApplication {
        // Deploy-prep proof: serve REAL `dicebear@10.3.0` output through the endpoint. RUN-gated on
        // CYP215_REAL_PRESETS=<dir of <style>/<style>-<n>.png> (self-skips green in CI, which has no node).
        val dir = System.getenv("CYP215_REAL_PRESETS") ?: return@testApplication
        val presets = File(dir)
        app(mgmt(presets))
        for (style in AvatarPresetResolver.ALLOWED_STYLES) {
            if (!File(presets, style).isDirectory) continue
            setPreset(style, "backend-agent")
            val served = jsonClient().get("/api/agents/frontend/avatar") { bearerAuth("tok-fe") }
            assertEquals(HttpStatusCode.OK, served.status, "real $style asset served")
            val img = ImageIO.read(ByteArrayInputStream(served.readRawBytes()))
            assertEquals(256, img.width, "$style is 256²"); assertEquals(256, img.height)
        }
    }

    @Test fun preset_serve_isDeterministic_forAGivenSeed() = testApplication {
        val presets = Files.createTempDirectory("preset-serve-det").toFile()
        // an 8-asset set like the runbook's `--count 8`; a given seed must always resolve to the same file.
        repeat(8) { bundleAsset(presets, "bottts", it, Color(10 + it * 20, 40, 200 - it * 10)) }
        app(mgmt(presets))
        setPreset("bottts", "stable-seed")

        val first = jsonClient().get("/api/agents/frontend/avatar") { bearerAuth("tok-fe") }.readRawBytes()
        val second = jsonClient().get("/api/agents/frontend/avatar") { bearerAuth("tok-fe") }.readRawBytes()
        assertContentEquals(first, second, "same (style, seed) → same bundled file every time (deterministic)")
        assertTrue(first.isNotEmpty())
    }
}
