package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-315 — `AgentDetail.worktreePath`: the agent's ABSOLUTE worktree path, server-resolved via the real
 * [com.tneff.cyppieagents.boot.WorktreeManager] (the client can't build it — it doesn't know the git-root).
 * Faithful, emulator-free, over the REAL platform: a LOCAL agent's `GET /api/agents/{id}` returns the exact
 * absolute path derived from the harness git-root (NOT a hardcoded string literal); a REMOTE/BYOA agent → null.
 */
class Cyp315WorktreePathE2eTest {

    private fun boot() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    private suspend fun HttpClient.detail(base: String, id: String): AgentDetail = get("$base/api/agents/$id").body()

    @Test
    fun worktreePath_localAgent_absoluteFromWorktreeManager_remoteAgent_null(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp315").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val base = p.baseUrl
            p.asOperator().use { op ->
                // LOCAL agent: the server resolves the worktree dir via the real WorktreeManager. The expected
                // value is DERIVED from the harness git-root's actual worktree layout (<git-root>/projects/
                // <projectId>/<worktree>) — the same resolution, not a duplicated literal.
                val expected = File(dir, "projects/default/backend").absolutePath
                val backend = op.detail(base, "backend")
                assertEquals(expected, backend.worktreePath, "local agent → absolute worktree path from WorktreeManager")
                assertTrue(File(backend.worktreePath!!).isAbsolute, "the path is absolute")
                // the PO's worktree resolves the same way (sanity: each agent gets its OWN folder)
                assertEquals(File(dir, "projects/default/po").absolutePath, op.detail(base, "po").worktreePath)

                // REMOTE/BYOA agent: no local worktree → null (same remoteAgents seam as the CLAUDE.md endpoints).
                op.post("$base/api/agents") { contentType(ContentType.Application.Json); setBody(NewAgentSpec("rem", "Rem", Role.WORKER, remote = true)) }
                assertNull(op.detail(base, "rem").worktreePath, "a remote agent has no local worktree → null")
            }
        }
        dir.deleteRecursively()
    }
}
