package com.tneff.cyppieagents.agentsettings

import com.tneff.cyppieagents.agentmgmt.AgentManagementRepository
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.NewAgentSpec
import com.tneff.cyppieagents.model.Role
import com.tneff.cyppieagents.model.WorktreeFate
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CYP-310 NO-GO regression — the EXACT reproduced path end-to-end over the REAL client→server (VM → [ClaudeMdHttpApi]
 * → embedded Ktor with the server's null-semantics), NOT a fake API and NOT the `:core` DTO in isolation:
 *
 *  1. the VM opens a NEW agent whose worktree CLAUDE.md is ABSENT → GET yields `exists=false, version=null` → the
 *     EMPTY state (the "Überschreiben legt sie an" affordance);
 *  2. the operator types a persona and clicks overwrite → the VM sends `expectedVersion=null` (exists=false) → the
 *     server's create-branch writes the file → **200**, restart hint armed, NO stale, NO 409 loop.
 *
 * The NO-GO bug: a non-null `version`/`expectedVersion` chain sent `""`, the server compared `"" != null` → 409, and
 * `forceOverwrite` re-fetched the still-absent file → `""` again → an unbreakable loop on every new agent. This test
 * is the guard that the whole nullable chain (API parse → VM buffer → send-conditional) actually reaches the wire.
 */
class ClaudeMdFirstWriteVmE2eTest {

    private class FakeRepo(private val detail: AgentDetail) : AgentManagementRepository {
        override suspend fun list(): List<Agent> = emptyList()
        override suspend fun detail(id: String): AgentDetail = detail
        override suspend fun add(spec: NewAgentSpec): Agent = error("unused")
        override suspend fun edit(id: String, edit: AgentEdit): Agent = error("unused")
        override suspend fun remove(id: String, worktree: WorktreeFate) {}
    }

    @Test
    fun newAgent_absentFile_operatorOverwrite_createsFile_noStaleLoop() = runBlocking {
        var content: String? = null // the worktree CLAUDE.md does not exist yet
        fun version(): String? = content?.let { "h:${it.hashCode()}" }
        fun viewJson(id: String): String {
            val vf = version()?.let { ""","version":"$it"""" } ?: ""
            return """{"agentId":"$id","content":"${content ?: ""}","exists":${content != null}$vf}"""
        }
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/agents/{id}/claude-md") {
                    call.respondText(viewJson(call.parameters.getOrFail("id")), ContentType.Application.Json)
                }
                post("/api/agents/{id}/claude-md") {
                    val id = call.parameters.getOrFail("id")
                    val body = call.receiveText()
                    val expected: String? = Regex(""""expectedVersion":"([^"]*)"""").find(body)?.groupValues?.get(1)
                    if (expected != version()) {
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
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val base = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
            val vm = AgentSettingsViewModel(
                "backend", FakeRepo(AgentDetail("backend", "Backend", Role.WORKER, "backend", "bash")),
                editable = true, initialName = "Backend", initialColorHex = null,
                claudeMdApi = ClaudeMdHttpApi(client, base, token = "op"), scope = scope,
            )

            // 1. The live GET settles → the file is absent → the EMPTY state.
            val loaded = withTimeout(5_000) { vm.state.first { !it.claudeMdLoading } }
            assertTrue(loaded.claudeMdEmpty, "a new agent's absent CLAUDE.md is the EMPTY state (exists=false)")
            assertFalse(loaded.claudeMdError, "absent ≠ a load error")

            // 2. Operator writes the first persona → real POST with expectedVersion=null → server creates it.
            vm.setClaudeMd("You are the Backend agent.")
            vm.overwriteClaudeMd()
            val done = withTimeout(5_000) {
                vm.state.first { !it.claudeMdWriting && (it.claudeMdWritten || it.claudeMdWriteError || it.claudeMdStale) }
            }
            assertTrue(done.claudeMdWritten, "the first write CREATES the file (200) — the restart hint arms")
            assertFalse(done.claudeMdStale, "NO 409 loop — the create-branch fired (the NO-GO regression guard)")
            assertFalse(done.claudeMdWriteError, "no write error")
            assertTrue(done.claudeMdFileExists, "the file now exists")
        } finally {
            scope.cancel()
            client.close()
            server.stop(100, 200)
        }
    }
}
