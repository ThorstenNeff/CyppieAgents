package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.agentmgmt.AgentManagementHttpRepository
import com.tneff.cyppieagents.agentmgmt.AgentMgmtException
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CYP-313 INTEGRATION gate — the MISSING leg: the **real Dev client [AgentManagementHttpRepository]** (the actual
 * HTTP encoder — `CommJson.encodeToString(AgentEdit.serializer())` over the wire) driven against the **real Backend
 * server** ([e2ePlatform]). Backend's `Cyp313RoleOptionalEditE2eTest` uses a RAW client; Dev's VM tests use a FAKE
 * repo with the real guard — NEITHER exercises the real client repo's `AgentEdit(role=null)` ENCODING against the
 * real server. This is the wire-encoding seam (the CYP-310/312 fake-shape trap class): a `role=null` display edit
 * must SERIALIZE such that the real server's guard short-circuits (`edit.role ?: return null`) → no false `last_po`.
 *
 * Calibration (per PO-Assistant): (①) role is guard-only — the server NEVER applies role, so a 200 role-PUT leaves
 * GET showing the OLD role; this test asserts role PRESERVED / the 409 guard codes, never "role changed". (②) the
 * client STUB applies role (unlike the server) — so this tooth uses the REAL server, never the stub.
 */
class Cyp313RolePreserveClientWireTest {

    private fun boot() =
        listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    private fun repo(p: E2ePlatform, http: HttpClient) =
        AgentManagementHttpRepository(http, p.baseUrl, token = E2ePlatform.OPERATOR_TOKEN)

    /**
     * THE FIX over the real client encoder: a `role=null` colour edit of the SOLE PO through the real repo →
     * 200 (no `last_po` thrown), the colour applied, and the PO role PRESERVED. Models the exact display-only edit
     * the FIXED VM now emits (`AgentEdit(role=null, color=…)`).
     */
    @Test
    fun realRepo_nullRoleColourEdit_ofSolePo_ok_rolePreserved(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp313-ok").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val repo = repo(p, http)
                assertEquals(Role.PO, repo.detail("po").role, "baseline: exactly one PO")
                // The real client encodes AgentEdit(role=null,...) — role must be OMITTED on the wire so the
                // server's guard never reads it as a demotion. No throw = no false last_po.
                val edited = repo.edit("po", AgentEdit(role = null, color = "#4488cc"))
                assertEquals(Role.PO, edited.role, "the sole PO's role is PRESERVED by a display-only edit")
                val after = repo.detail("po")
                assertEquals(Role.PO, after.role, "GET confirms the role stayed PO")
                assertEquals("#4488cc", after.color, "the colour edit applied")
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }

    /**
     * THE LIVE BUG, reproduced through the real repo+server: the PRE-fix VM sent `role = s.role`, and in the reported
     * state-population gap `s.role` was WORKER (the VM role starts WORKER until `load()` promotes it to PO). A colour
     * edit carrying `role=WORKER` on the sole PO is read by the guard as a demotion → `last_po`. This is exactly why
     * the VM must send `role=null`; the rejection also proves the guard stays sharp for a REAL demote.
     */
    @Test
    fun realRepo_workerRoleEdit_ofSolePo_reds_lastPo(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp313-bug").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val repo = repo(p, http)
                // The buggy VM wire: a colour edit that also carries role=WORKER (the stale pre-load s.role).
                val ex = assertFailsWith<AgentMgmtException> {
                    repo.edit("po", AgentEdit(role = Role.WORKER, color = "#4488cc"))
                }
                assertEquals("last_po", ex.code, "an explicit demote of the only PO reds last_po (the live bug's wire)")
                assertEquals(Role.PO, repo.detail("po").role, "the rejected edit changed nothing")
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }

    /**
     * Guard stays sharp over the real client encoder: an EXPLICIT second PO (`role=PO` on a worker) → `po_already_exists`.
     */
    @Test
    fun realRepo_explicitSecondPo_reds_poAlreadyExists(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp313-2ndpo").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val repo = repo(p, http)
                val ex = assertFailsWith<AgentMgmtException> {
                    repo.edit("backend", AgentEdit(role = Role.PO))
                }
                assertEquals("po_already_exists", ex.code, "a second explicit PO reds po_already_exists")
                assertEquals(Role.WORKER, repo.detail("backend").role, "the rejected promote changed nothing")
                // Calibration ①: role is guard-only (server never applies) — this tooth asserts only the guard
                // codes + role PRESERVED, never that a 200 role-PUT actually changed the stored role.
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }
}
