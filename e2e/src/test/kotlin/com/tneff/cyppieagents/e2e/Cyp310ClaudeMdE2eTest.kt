package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.ClaudeMdUpdate
import com.tneff.cyppieagents.model.ClaudeMdView
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-310 — the CLAUDE.md management endpoints (LOCAL). The connector no longer auto-writes CLAUDE.md; it is
 * read live + hard-overwritten via the endpoints. Faithful, emulator-free, over the REAL platform + real files:
 * new agent = empty · GET reads the live file (incl. an external edit) · POST hard-overwrites · optimistic
 * concurrency (stale expectedVersion → 409, no write) · 256 KB cap → 413 · remote → 409 agent_not_local ·
 * participant read / operator write gating.
 */
class Cyp310ClaudeMdE2eTest {

    private fun boot() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    private suspend fun HttpClient.getMd(base: String, id: String): HttpResponse = get("$base/api/agents/$id/claude-md")
    private suspend fun HttpClient.postMd(base: String, id: String, body: ClaudeMdUpdate): HttpResponse =
        post("$base/api/agents/$id/claude-md") { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun claudeMd_liveRead_hardWrite_staleGuard_remote_cap_gating(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp310").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val base = p.baseUrl
            val wtFile = File(dir, "projects/default/backend/CLAUDE.md")

            p.asOperator().use { op ->
                // 1. New agent → NO auto-write → GET exists=false, content="", version=null.
                val v0 = op.getMd(base, "backend").body<ClaudeMdView>()
                assertFalse(v0.exists, "a new agent has no CLAUDE.md (auto-write is gone)")
                assertEquals("", v0.content)
                assertEquals(null, v0.version)

                // 2. POST hard write (first create → expectedVersion=null).
                val w1 = op.postMd(base, "backend", ClaudeMdUpdate("# v1", expectedVersion = null))
                assertEquals(HttpStatusCode.OK, w1.status)
                val v1 = w1.body<ClaudeMdView>()
                assertTrue(v1.exists); assertEquals("# v1", v1.content); assertNotNull(v1.version)
                assertEquals("# v1", wtFile.readText(), "written to the REAL worktree file")

                // 3. GET reads the live file.
                assertEquals("# v1", op.getMd(base, "backend").body<ClaudeMdView>().content)

                // 4. External / agent self-edit → GET reflects it LIVE (no server-cached copy).
                wtFile.writeText("# external edit")
                val vExt = op.getMd(base, "backend").body<ClaudeMdView>()
                assertEquals("# external edit", vExt.content, "GET reads the live file incl. external edits")
                assertTrue(vExt.version != v1.version, "the version tracks the external change")

                // 5. Stale write (the OLD expectedVersion, now superseded by the external edit) → 409, NO write.
                val stale = op.postMd(base, "backend", ClaudeMdUpdate("# v2", expectedVersion = v1.version))
                assertEquals(HttpStatusCode.Conflict, stale.status, "stale expectedVersion → 409 claude_md_stale")
                assertEquals("# external edit", wtFile.readText(), "the stale write did NOT overwrite the external edit")

                // 6. Correct expectedVersion → hard overwrite.
                assertEquals(HttpStatusCode.OK, op.postMd(base, "backend", ClaudeMdUpdate("# v2", expectedVersion = vExt.version)).status)
                assertEquals("# v2", wtFile.readText())

                // 7. Over the 256 KB cap → 413.
                assertEquals(
                    HttpStatusCode.PayloadTooLarge,
                    op.postMd(base, "backend", ClaudeMdUpdate("x".repeat(256 * 1024 + 1))).status,
                    ">256 KB → 413",
                )

                // 8. Remote/BYOA agent → 409 agent_not_local (no local worktree to manage).
                op.post("$base/api/agents") { contentType(ContentType.Application.Json); setBody(NewAgentSpec("rem", "Rem", Role.WORKER, remote = true)) }
                assertEquals(HttpStatusCode.Conflict, op.getMd(base, "rem").status, "remote GET → 409 agent_not_local")
                assertEquals(HttpStatusCode.Conflict, op.postMd(base, "rem", ClaudeMdUpdate("x")).status, "remote POST → 409")

                // 9. Unknown agent → 404.
                assertEquals(HttpStatusCode.NotFound, op.getMd(base, "nope").status)
            }

            // 10. Gating: participant (agent token) READS (same posture as agent-detail); non-operator POST → 401/403.
            p.asAgent("backend").use { agent ->
                assertEquals(HttpStatusCode.OK, agent.getMd(base, "backend").status, "participant read = agent-detail posture")
                val s = agent.postMd(base, "backend", ClaudeMdUpdate("# nope")).status
                assertTrue(s == HttpStatusCode.Unauthorized || s == HttpStatusCode.Forbidden, "non-operator POST → 401/403 (was $s)")
                assertEquals("# v2", File(dir, "projects/default/backend/CLAUDE.md").readText(), "the rejected write changed nothing")
            }
        }
        dir.deleteRecursively()
    }
}
