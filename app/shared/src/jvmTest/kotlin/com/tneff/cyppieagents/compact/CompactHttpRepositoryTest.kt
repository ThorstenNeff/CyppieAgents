package com.tneff.cyppieagents.compact

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactRunSummary
import com.tneff.cyppieagents.model.CompactStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CYP-326 — the live [CompactHttpRepository] real-seam against the `:core` DTOs (no hand-parse). Proves the
 * stub→real swap decodes a real `GET /api/compact/status` frame and round-trips `POST /api/compact/config`,
 * and that a 403 maps to a [CompactException] (`operator_required`) so the VM's fail-closed path runs.
 */
class CompactHttpRepositoryTest {

    @Test
    fun getStatus_decodesRealCoreDto_andConfigRoundTrips() = runBlocking {
        val status = CompactStatus(
            allowed = true, thresholdTokens = 500_000, armed = false, running = false,
            lastRun = CompactRunSummary(completed = 3, total = 5, pendingAgentIds = listOf("frontend", "qa"), startedTs = 1_000, finishedTs = 2_000),
        )
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/compact/status") {
                    call.respondText(CommJson.encodeToString(CompactStatus.serializer(), status), ContentType.Application.Json)
                }
                post("/api/compact/config") {
                    val cfg = CommJson.decodeFromString(CompactConfig.serializer(), call.receiveText())
                    // Echo the applied config back as the new status (server mirror).
                    val applied = status.copy(allowed = cfg.allowed, thresholdTokens = cfg.thresholdTokens)
                    call.respondText(CommJson.encodeToString(CompactStatus.serializer(), applied), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = CompactHttpRepository(client, "http://127.0.0.1:$port", token = "op")
                val got = repo.getStatus()
                assertEquals(status, got, "GET decodes the real :core CompactStatus incl. the X/N run summary")
                assertEquals(2, got.lastRun?.pendingAgentIds?.size, "pendingAgentIds (the WARN set) survives the wire")
                val applied = repo.setConfig(CompactConfig(allowed = false, thresholdTokens = 700_000))
                assertTrue(!applied.allowed && applied.thresholdTokens == 700_000, "POST round-trips the config → server mirror")
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun setConfig_403_mapsToOperatorRequired() = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/compact/config") { call.respond(HttpStatusCode.Forbidden, "") }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = CompactHttpRepository(client, "http://127.0.0.1:$port", token = "member")
                val ex = assertFailsWith<CompactException> { repo.setConfig(CompactConfig(allowed = true)) }
                assertEquals("operator_required", ex.code)
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
