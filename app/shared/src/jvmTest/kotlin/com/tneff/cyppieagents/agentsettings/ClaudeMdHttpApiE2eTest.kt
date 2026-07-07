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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-310 — e2e proof that [ClaudeMdHttpApi] talks the v3 contract against a REAL embedded Ktor server that
 * FAITHFULLY models the server's null-semantics (`AgentManagement`): an ABSENT file OMITS `version` (⇒
 * `currentVersion=null`), and a write's `expectedVersion` is compared for equality against it — a missing field
 * decodes to `null`. This is the exact wire the NO-GO first-write bug lived on: the old test-server ALWAYS sent a
 * version, so the absent → null path (case **a**) was never exercised. All three cases here:
 *  - **(a) absent → first write** with `expectedVersion` OMITTED (null) → **200**, the file is created;
 *  - **(b) existing + correct version** → **200** echo (new version);
 *  - **(c) existing + stale version** → **409 `claude_md_stale`** → [ClaudeMdException], NO write.
 */
class ClaudeMdHttpApiE2eTest {

    @Test
    fun absentFirstWrite_thenMatch_thenStale() = runBlocking {
        // A server whose CLAUDE.md begins ABSENT (version omitted) and enforces the exact if-match guard.
        var content: String? = null // null = the file does not exist yet
        fun version(): String? = content?.let { "h:${it.hashCode()}" } // absent ⇒ null, else a content hash
        fun viewJson(id: String): String {
            val v = version()
            val versionField = if (v == null) "" else ""","version":"$v""""
            return """{"agentId":"$id","content":"${content ?: ""}","exists":${content != null}$versionField}"""
        }
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/agents/{id}/claude-md") {
                    if (call.request.header("Authorization") != "Bearer op") {
                        call.respondText("""{"error":{"code":"unauthorized"}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                        return@get
                    }
                    call.respondText(viewJson(call.parameters.getOrFail("id")), ContentType.Application.Json)
                }
                post("/api/agents/{id}/claude-md") {
                    val id = call.parameters.getOrFail("id")
                    val body = call.receiveText()
                    // An OMITTED expectedVersion field decodes to null — the "expect absent" first-write branch.
                    val expected: String? = Regex(""""expectedVersion":"([^"]*)"""").find(body)?.groupValues?.get(1)
                    if (expected != version()) { // absent ⇒ version()==null ⇒ a first write must send null
                        call.respondText("""{"error":{"code":"claude_md_stale"}}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                        return@post
                    }
                    content = Regex(""""content":"([^"]*)"""").find(body)?.groupValues?.get(1) ?: ""
                    call.respondText(viewJson(id), ContentType.Application.Json)
                }
            }
        }
        server.start(wait = false)
        val client = HttpClient(CIO)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val api = ClaudeMdHttpApi(client, base, token = "op")

            // GET on the absent file → exists=false, version=null (NOT "").
            val absent = api.get("backend")
            assertTrue(!absent.exists, "an absent file reports exists=false"); assertNull(absent.version, "…and version=null")

            // (a) FIRST WRITE with expectedVersion=null (the client OMITS the field) → 200, file created. This is the
            // path the NO-GO bug 409-looped: sending "" here would compare `"" != null` and never write.
            val created = api.update("backend", "fresh persona", expectedVersion = null)
            assertEquals("fresh persona", created.content); assertTrue(created.exists)
            val v1 = created.version; assertTrue(v1 != null, "a written file has a real version")

            // (b) existing + CORRECT version → 200 echo (new version).
            val written = api.update("backend", "rewritten", expectedVersion = v1)
            assertEquals("rewritten", written.content); assertTrue(written.version != null && written.version != v1)

            // (c) STALE base (the version moved) → 409 → ClaudeMdException("claude_md_stale"), no silent clobber.
            val stale = assertFails { api.update("backend", "clobber", expectedVersion = v1) }
            assertIs<ClaudeMdException>(stale); assertEquals("claude_md_stale", stale.code)
        } finally {
            client.close()
            server.stop(100, 200)
        }
    }
}
