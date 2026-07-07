package com.tneff.cyppieagents.agentmgmt

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.AgentDetail
import com.tneff.cyppieagents.model.AgentEdit
import com.tneff.cyppieagents.model.AgentRunState
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs

/**
 * CYP-86/87/88 stub→real swap — e2e proof that [AgentManagementHttpRepository] talks the CYP-97 agent-CRUD
 * endpoints against a **real embedded Ktor server** (not a fake), so the swap from
 * [StubAgentManagementRepository] is only a constructor change. Covers the CRUD round-trip, the CYP-101
 * detail-prefill (persona/launch), the server reason-code mapping, and the `?worktree=` fate param.
 */
class AgentManagementHttpRepositoryE2eTest {

    @Test
    fun crud_roundTrips_mapsErrors_andSendsWorktreeFate() = runBlocking {
        val agents = mutableListOf(Agent("po", "Product Owner", Role.PO, "po", AgentRunState.RUNNING))
        val config = mutableMapOf<String, Pair<String, String?>>("po" to ("claude" to null))
        var deletedWorktreeParam: String? = null

        fun err(code: String) = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, code)))

        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/agents") {
                    call.respondText(CommJson.encodeToString(ListSerializer(Agent.serializer()), agents), ContentType.Application.Json)
                }
                get("/api/agents/{id}") {
                    val id = call.parameters.getOrFail("id")
                    val a = agents.firstOrNull { it.id == id }
                    if (a == null) { call.respondText(err("agent_not_found"), ContentType.Application.Json, HttpStatusCode.NotFound); return@get }
                    val (launch, persona) = config[id] ?: ("claude" to null)
                    val detail = AgentDetail(a.id, a.name, a.role, a.worktree, launch, persona)
                    call.respondText(CommJson.encodeToString(AgentDetail.serializer(), detail), ContentType.Application.Json)
                }
                post("/api/agents") {
                    val spec = CommJson.decodeFromString(NewAgentSpec.serializer(), call.receiveText())
                    when {
                        agents.any { it.id == spec.id } -> call.respondText(err("agent_exists"), ContentType.Application.Json, HttpStatusCode.Conflict)
                        spec.role == Role.PO && agents.any { it.role == Role.PO } -> call.respondText(err("po_already_exists"), ContentType.Application.Json, HttpStatusCode.Conflict)
                        else -> {
                            val created = Agent(spec.id, spec.name, spec.role, spec.worktree ?: spec.id, AgentRunState.STOPPED)
                            agents.add(created)
                            config[spec.id] = (spec.launch ?: "claude") to spec.persona
                            call.respondText(CommJson.encodeToString(Agent.serializer(), created), ContentType.Application.Json, HttpStatusCode.Created)
                        }
                    }
                }
                put("/api/agents/{id}") {
                    val id = call.parameters.getOrFail("id")
                    val edit = CommJson.decodeFromString(AgentEdit.serializer(), call.receiveText())
                    val index = agents.indexOfFirst { it.id == id }
                    if (index < 0) { call.respondText(err("agent_not_found"), ContentType.Application.Json, HttpStatusCode.NotFound); return@put }
                    val updated = agents[index].copy(role = edit.role ?: agents[index].role) // CYP-313: null role = PRESERVE
                    agents[index] = updated
                    val (curLaunch, curPersona) = config[id] ?: ("claude" to null)
                    config[id] = (edit.launch ?: curLaunch) to (edit.persona ?: curPersona) // preserve on null
                    call.respondText(CommJson.encodeToString(Agent.serializer(), updated), ContentType.Application.Json)
                }
                delete("/api/agents/{id}") {
                    val id = call.parameters.getOrFail("id")
                    deletedWorktreeParam = call.request.queryParameters["worktree"]
                    agents.removeAll { it.id == id }
                    call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val repo = AgentManagementHttpRepository(client, "http://127.0.0.1:$port", token = "op")

                // Add (201) → STOPPED, not spawned.
                val created = repo.add(NewAgentSpec("fe", "Frontend", Role.WORKER, persona = "careful dev", launch = "claude --x"))
                assertEquals(AgentRunState.STOPPED, created.runState)

                // CYP-101 detail-prefill: persona/launch come back from GET /api/agents/{id}.
                val detail = repo.detail("fe")
                assertEquals("careful dev", detail.persona)
                assertEquals("claude --x", detail.launch)

                // List round-trips the :core Agent set.
                assertEquals(setOf("po", "fe"), repo.list().map { it.id }.toSet())

                // 409 → mapped to the reason code the VM keys on.
                val conflict = assertFails { repo.add(NewAgentSpec("po2", "PO2", Role.PO)) }
                assertIs<AgentMgmtException>(conflict)
                assertEquals("po_already_exists", conflict.code)

                // 404 on an unknown detail.
                val notFound = assertFails { repo.detail("ghost") }
                assertIs<AgentMgmtException>(notFound)
                assertEquals("agent_not_found", notFound.code)

                // Edit round-trips; remove sends the worktree fate as the query param (default keep vs delete).
                repo.edit("fe", AgentEdit(Role.WORKER, persona = null, launch = "claude --y"))
                repo.remove("fe", WorktreeFate.DELETE)
                assertEquals("delete", deletedWorktreeParam)
                assertEquals(setOf("po"), repo.list().map { it.id }.toSet())
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
