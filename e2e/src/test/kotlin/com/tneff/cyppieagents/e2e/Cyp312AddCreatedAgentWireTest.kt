package com.tneff.cyppieagents.e2e

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.agentmgmt.AgentManagementHttpRepository
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.CreatedAgent
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CYP-312 INTEGRATION gate — the **real Dev [AgentManagementHttpRepository] (+ VM)** driven against the
 * **real Backend server** ([e2ePlatform]). The bug: `add()` decoded the `POST /api/agents` response as a
 * bare `Agent` — but the server responds the [CreatedAgent] WRAPPER `{agent, token}` (RestContract §98 /
 * `AgentManagement.add`). The mis-decode threw, surfacing a FALSE "Anlegen fehlgeschlagen" even though the
 * server created the agent. The old repo test used a bare-`Agent` fake that MASKED the drift (same fake-shape
 * gap class as CYP-310); this fires through the real route (real `CreatedAgent` on the wire).
 */
class Cyp312AddCreatedAgentWireTest {

    private fun boot() = listOf(SeedProject("default", "Default", listOf(SeedAgent("po", Role.PO))))

    private fun repo(p: E2ePlatform, http: HttpClient) =
        AgentManagementHttpRepository(http, p.baseUrl, token = E2ePlatform.OPERATOR_TOKEN)

    /**
     * THE FIX: a real add through the real route must DECODE the CreatedAgent wrapper, return the unwrapped
     * `.agent` (no throw / no false failure), report `runState=STOPPED` (created ≠ spawned), and the agent must
     * be listed. RED with the old `Agent.serializer()` decode ({agent,token} has no top-level id/role → throws).
     */
    @Test
    fun realRepo_add_decodesCreatedAgent_returnsStoppedAgent(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp312-add").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                val created = repo(p, http).add(
                    NewAgentSpec("fe", "Frontend", Role.WORKER, persona = "careful dev", launch = "claude --x"),
                )
                assertEquals("fe", created.id, "the wrapper was unwrapped to .agent")
                assertEquals(Role.WORKER, created.role)
                assertEquals(AgentRunState.STOPPED, created.runState, "add creates a STOPPED agent (not spawned)")
                assertTrue(repo(p, http).list().any { it.id == "fe" }, "the created agent is listed by the server")
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }

    /**
     * REMOTE-TOKEN tolerance (CYP-171/197 seam): a remote create makes the REAL server mint a NON-NULL one-time
     * token inside the wrapper. First prove the server actually sends one (non-vacuity), then prove the real repo
     * unwraps `.agent` regardless of the extra field.
     */
    @Test
    fun realRepo_add_remote_toleratesNonNullMintedToken(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cyp312-remote").toFile()
        e2ePlatform(boot(), gitRootOverride = dir).use { p ->
            val http = HttpClient(CIO)
            try {
                // Non-vacuity: the real server DOES mint a non-null token for a remote create.
                val raw = http.post("${p.baseUrl}/api/agents") {
                    header(HttpHeaders.Authorization, "Bearer ${E2ePlatform.OPERATOR_TOKEN}")
                    contentType(ContentType.Application.Json)
                    setBody(CommJson.encodeToString(NewAgentSpec.serializer(), NewAgentSpec("rem-raw", "RemRaw", Role.WORKER, remote = true)))
                }.bodyAsText()
                val decoded = CommJson.decodeFromString(CreatedAgent.serializer(), raw)
                assertNotNull(decoded.token, "a remote create mints a one-time bearer token")
                assertTrue(decoded.token!!.isNotEmpty(), "the minted token is non-empty")

                // The real repo must still unwrap .agent for a remote create despite the non-null token.
                val created = repo(p, http).add(NewAgentSpec("rem-repo", "RemRepo", Role.WORKER, remote = true))
                assertEquals("rem-repo", created.id, "remote wrapper unwrapped to .agent, token tolerated")
                assertEquals(AgentRunState.STOPPED, created.runState)
            } finally {
                http.close()
            }
        }
        dir.deleteRecursively()
    }
    // NOTE: the VM success-path (addOpen=false / no addError / reload) is covered in :app:shared jvmTest
    // (AgentManagementViewModelTest) — the VM's androidx.lifecycle supertype isn't visible from :e2e, so the
    // full-stack VM assertion lives where the lifecycle deps are; here we prove the real decode at the repo↔server seam.
}
