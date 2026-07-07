package com.tneff.cyppieagents.agentsettings

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-310 — e2e proof that [ClaudeMdHttpApi] talks the v3 contract against a REAL embedded Ktor server:
 *  - GET → `{agentId, content, exists, version}` (bearer-gated);
 *  - POST `{content, expectedVersion}` → 200 echo when the version matches, **409 `claude_md_stale`** when it does
 *    not → mapped to [ClaudeMdException] (`code == "claude_md_stale"`), which the VM turns into the Layer-2 conflict.
 */
class ClaudeMdHttpApiE2eTest {

    @Test
    fun getReadsFile_postEchoesOnMatch_409StaleOnMismatch() = runBlocking {
        var stored = "live persona"
        var version = "v1"
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/agents/{id}/claude-md") {
                    if (call.request.header("Authorization") != "Bearer op") {
                        call.respondText("""{"error":{"code":"unauthorized"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get
                    }
                    val id = call.parameters.getOrFail("id")
                    call.respondText("""{"agentId":"$id","content":"$stored","exists":true,"version":"$version"}""", ContentType.Application.Json)
                }
                post("/api/agents/{id}/claude-md") {
                    val id = call.parameters.getOrFail("id")
                    val body = call.receiveText()
                    val expected = Regex(""""expectedVersion":"([^"]*)"""").find(body)?.groupValues?.get(1)
                    if (expected != version) {
                        call.respondText("""{"error":{"code":"claude_md_stale"}}""", ContentType.Application.Json, HttpStatusCode.Conflict); return@post
                    }
                    stored = Regex(""""content":"([^"]*)"""").find(body)?.groupValues?.get(1) ?: ""
                    version = "v2"
                    call.respondText("""{"agentId":"$id","content":"$stored","exists":true,"version":"$version"}""", ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        val client = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val api = ClaudeMdHttpApi(client, base, token = "op")

            // 1. GET reads the live file (content + version + exists).
            val read = api.get("backend")
            assertEquals("live persona", read.content); assertTrue(read.exists); assertEquals("v1", read.version)

            // 2. POST with the matching base version → 200 echo (new version).
            val written = api.update("backend", "rewritten", expectedVersion = "v1")
            assertEquals("rewritten", written.content); assertEquals("v2", written.version)

            // 3. A STALE base (the version moved to v2) → 409 → ClaudeMdException("claude_md_stale"), not a silent write.
            val stale = assertFails { api.update("backend", "clobber", expectedVersion = "v1") }
            assertIs<ClaudeMdException>(stale)
            assertEquals("claude_md_stale", stale.code)
        } finally {
            client.close()
            server.stop(100, 200)
        }
    }
}
