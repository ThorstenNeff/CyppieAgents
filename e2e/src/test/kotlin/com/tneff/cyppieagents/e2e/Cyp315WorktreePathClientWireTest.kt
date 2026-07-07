package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.agentmgmt.AgentManagementHttpRepository
import com.tneff.cyppieagents.agentmgmt.AgentMgmtException
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CYP-315 INTEGRATION gate (my QA tooth) — the **real Dev [AgentManagementHttpRepository]** driven against the
 * **real Backend server** ([e2ePlatform]). Dev's own teeth cover the client at a STUB (`DetailRepo`/`FailingDetailRepo`
 * → a fixed/throwing `AgentDetail`) and the server at a RAW `HttpClient.body<AgentDetail>()` — neither drives the real
 * repo CLASS end-to-end, and (critically) NOTHING exercises a FAILED detail load over the real seam. That is the
 * fake-shape gap class (CYP-310/312/313): the honesty invariant `failed ≠ remote` rests on the real repo actually
 * THROWING on a non-2xx (so the VM's `runCatching{}.getOrNull()` → null → `detailResolved=false`) rather than
 * silently returning a phantom null-path `AgentDetail` that the panel would misread as a remote agent.
 *
 * Covered here, real client → real server:
 *  - **Z1 (path is real & server-resolved):** a LOCAL agent's `detail()` decodes the ABSOLUTE path the real
 *    WorktreeManager built (`<git-root>/projects/<projectId>/<worktree>`, DERIVED — not a duplicated literal).
 *  - **Z2 (remote → real null):** a remote/BYOA agent's `detail()` decodes `worktreePath == null`.
 *  - **⭐ `failed ≠ remote` over the real seam (§2 honesty core):** a failed detail load (404 unknown agent; 401
 *    bad token) THROWS [AgentMgmtException] — it does NOT hand back a null-path detail. This is what makes the VM's
 *    resolved-null (Z2) genuinely DISTINCT from a load failure (Z4); Dev's stub asserts the VM half, this asserts
 *    the repo half the stub merely *assumes*.
 */
class Cyp315WorktreePathClientWireTest {

    private fun boot() = listOf(
        SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))),
    )

    private fun repo(p: E2ePlatform, http: HttpClient, token: String = E2ePlatform.OPERATOR_TOKEN) =
        AgentManagementHttpRepository(http, p.baseUrl, token = token)

    /** Z1 — the real repo decodes the server-resolved ABSOLUTE worktree path (== the real WorktreeManager layout). */
    @Test
    fun realRepo_localAgent_decodesServerResolvedAbsolutePath(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp315-wire").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val backend = repo(p, http).detail("backend")
                val expected = File(dir, "projects/default/backend").absolutePath // derived from the harness git-root
                assertEquals(expected, backend.worktreePath, "the real client decodes the server-resolved absolute path")
                assertTrue(File(backend.worktreePath!!).isAbsolute, "the decoded path is absolute (server is the source of truth)")
                // each agent gets its OWN folder — the po resolves the sibling path, not a shared one
                assertEquals(File(dir, "projects/default/po").absolutePath, repo(p, http).detail("po").worktreePath)
            } finally { http.close() }
        }
        dir.deleteRecursively()
    }

    /** Z2 — a remote/BYOA agent has no local worktree → the real repo decodes a genuine null (the "not local" source). */
    @Test
    fun realRepo_remoteAgent_decodesNullPath(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp315-wire-rem").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                p.asOperator().use { op ->
                    op.post("${p.baseUrl}/api/agents") {
                        contentType(ContentType.Application.Json)
                        setBody(NewAgentSpec("rem", "Rem", Role.WORKER, remote = true))
                    }
                }
                assertNull(repo(p, http).detail("rem").worktreePath, "remote agent → server sends null → real repo decodes null")
            } finally { http.close() }
        }
        dir.deleteRecursively()
    }

    /**
     * ⭐ THE CORE INVARIANT over the real seam — `failed ≠ remote`. A failed detail load must be a THROW at the
     * real repo boundary, NEVER a phantom null-path `AgentDetail`. If the repo swallowed a non-2xx and returned a
     * defaulted detail (`worktreePath == null`), the VM would set `detailResolved` off a "success" and the panel
     * would falsely render "not local" for an agent whose detail merely failed to load. Two real failure vectors:
     * a 404 (unknown agent) and a 401 (bad token) both surface as [AgentMgmtException] — not a detail.
     *
     * Mutation that would RED this (verified): make `AgentManagementHttpRepository.getDecoded` skip `ensureSuccess`,
     * so the 404 `{error:{code,message}}` body is fed to the AgentDetail decoder — which throws a bare
     * `MissingFieldException` (AgentDetail's id/name/role/worktree are required), an UNMAPPED failure, not the clean
     * `AgentMgmtException("agent_not_found")`. Either way (unmapped throw here, or a phantom null-path detail if the
     * DTO were fully defaulted) the invariant is the same: the repo must surface a TYPED, mapped failure so the VM's
     * `runCatching{}.getOrNull()` → null → `detailResolved=false`; asserting the specific mapped code makes the tooth
     * bite → RED for the right reason.
     */
    @Test
    fun realRepo_failedLoad_throws_notPhantomNullPathDetail(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp315-wire-fail").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                // 404: an unknown agent id — a failed load, NOT a resolved-null remote agent.
                val notFound = assertFailsWith<AgentMgmtException>("a 404 detail load must THROW, never return a null-path detail") {
                    repo(p, http).detail("ghost-agent")
                }
                assertEquals("agent_not_found", notFound.code, "the failure surfaces the server's reason code")

                // 401: a bad bearer token — another failed load; still a throw, never a phantom detail.
                val unauthorized = assertFailsWith<AgentMgmtException>("a 401 detail load must THROW, never return a null-path detail") {
                    repo(p, http, token = "not-a-real-token").detail("backend")
                }
                assertEquals("unauthorized", unauthorized.code)
            } finally { http.close() }
        }
        dir.deleteRecursively()
    }
}
