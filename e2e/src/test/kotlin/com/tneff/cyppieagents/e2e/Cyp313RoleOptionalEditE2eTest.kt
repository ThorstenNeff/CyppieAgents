package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CYP-313 (Bug, High) — `AgentEdit.role` is nullable (null = PRESERVE, consistent with name/color/launch).
 * The reported bug: a display-only edit (colour) of the SOLE PO had to carry a role, and a non-PO one was
 * mis-read as demoting the only PO → false `last_po`. Faithful, emulator-free, over the REAL platform + REST:
 * (c) a null-role colour edit of the only PO → 200 and the role STAYS PO (topology untouched) · (a) an
 * explicit demote of the only PO still reds `last_po` · (b) an explicit second PO still reds `po_already_exists`.
 */
class Cyp313RoleOptionalEditE2eTest {

    private fun boot() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO), SeedAgent("backend"))))

    private suspend fun HttpClient.putAgent(base: String, id: String, body: AgentEdit): HttpResponse =
        put("$base/api/agents/$id") { contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.detail(base: String, id: String): AgentDetail =
        get("$base/api/agents/$id").body()

    @Test
    fun roleOptional_displayEditOfOnlyPo_ok_explicitTopologyGuardsStaySharp(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp313").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val base = p.baseUrl
            p.asOperator().use { op ->
                // Baseline: exactly one PO.
                assertEquals(Role.PO, op.detail(base, "po").role)

                // (c) THE BUG: a null-role colour edit of the SOLE PO → 200, role STAYS PO, colour applied,
                //     topology untouched. Before CYP-313 this returned 409 last_po.
                val edited = op.putAgent(base, "po", AgentEdit(role = null, color = "#123456"))
                assertEquals(HttpStatusCode.OK, edited.status, "null-role display edit of the only PO must be allowed")
                val afterC = op.detail(base, "po")
                assertEquals(Role.PO, afterC.role, "the sole PO's role is PRESERVED by a display-only edit")
                assertEquals("#123456", afterC.color, "the colour edit did apply")

                // (a) an EXPLICIT demote of the only PO still reds last_po — the guard stays sharp.
                val demote = op.putAgent(base, "po", AgentEdit(role = Role.WORKER))
                assertEquals(HttpStatusCode.Conflict, demote.status)
                assertEquals("last_po", demote.body<ApiErrorBody>().error.code)
                assertEquals(Role.PO, op.detail(base, "po").role, "the rejected demote changed nothing")

                // (b) an EXPLICIT second PO still reds po_already_exists — the guard stays sharp.
                val secondPo = op.putAgent(base, "backend", AgentEdit(role = Role.PO))
                assertEquals(HttpStatusCode.Conflict, secondPo.status)
                assertEquals("po_already_exists", secondPo.body<ApiErrorBody>().error.code)
                assertEquals(Role.WORKER, op.detail(base, "backend").role, "the rejected promote changed nothing")
            }
        }
        dir.deleteRecursively()
    }
}
