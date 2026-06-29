package com.tneff.cyppieagents.crossproject

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ApiError
import com.tneff.cyppieagents.model.ApiErrorBody
import com.tneff.cyppieagents.model.AuthorizeShareRequest
import com.tneff.cyppieagents.model.ChannelShareView
import com.tneff.cyppieagents.model.ReachedAgent
import com.tneff.cyppieagents.model.ShareAccess
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CYP-93 stub→real swap — e2e proof that [HttpCrossProjectRepository] talks the `/api/channels/{id}/share`
 * endpoints against a **real embedded Ktor server**, so the swap from [StubCrossProjectRepository] is only a
 * constructor change. Covers GET-disclosure (fail-closed default), PUT-authorize → concrete reach, the
 * operator-gate (403 → mapping), and DELETE-revoke → immediately fail-closed.
 */
class CrossProjectHttpRepositoryE2eTest {

    private fun err(code: String) = CommJson.encodeToString(ApiErrorBody.serializer(), ApiErrorBody(ApiError(code, code)))

    @Test
    fun share_lifecycle_gatesOperator_andMapsReach() = runBlocking {
        val records = mutableMapOf<String, Set<String>>() // channelId → sharedWith projects
        // A cross-project ACL member: channel c1 has agent-b in project p2 (read).
        val members = listOf(Triple("c1", "agent-b", "p2"))

        fun shareView(channelId: String): String {
            val sw = records[channelId]
            val view = if (sw == null) {
                ChannelShareView(shared = false)
            } else {
                val reach = members.filter { it.first == channelId && it.third in sw }
                    .map { ReachedAgent(it.second, it.third, ShareAccess.READ) }
                ChannelShareView(shared = true, sharedAt = 1_700_000_000_000L, reachableScope = reach)
            }
            return CommJson.encodeToString(ChannelShareView.serializer(), view)
        }

        val server = embeddedServer(Netty, port = 0) {
            routing {
                route("/api/channels/{id}/share") {
                    get { // participant-readable disclosure
                        call.respondText(shareView(call.parameters.getOrFail("id")), ContentType.Application.Json)
                    }
                    put {
                        if (call.request.headers["Authorization"] != "Bearer op") {
                            call.respondText(err("operator_required"), ContentType.Application.Json, HttpStatusCode.Forbidden); return@put
                        }
                        val id = call.parameters.getOrFail("id")
                        val req = CommJson.decodeFromString(AuthorizeShareRequest.serializer(), call.receiveText())
                        records[id] = req.sharedWith
                        call.respondText(shareView(id), ContentType.Application.Json)
                    }
                    delete {
                        if (call.request.headers["Authorization"] != "Bearer op") {
                            call.respondText(err("operator_required"), ContentType.Application.Json, HttpStatusCode.Forbidden); return@delete
                        }
                        val id = call.parameters.getOrFail("id")
                        records.remove(id)
                        call.respondText(shareView(id), ContentType.Application.Json)
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val base = "http://127.0.0.1:$port"
            val client = HttpClient(CIO)
            try {
                val repo = HttpCrossProjectRepository(client, base, token = "op", granteeProjects = { setOf("p2") })

                // GET disclosure pre-share: explicit fail-closed default.
                val before = repo.status("c1")
                assertFalse(before.shared)
                assertTrue(before.reachableMembers.isEmpty())

                // PUT authorize → concrete reach (agent-b from p2, read) carries through the mapping.
                val authd = repo.authorize("c1")
                assertTrue(authd.shared)
                assertEquals("agent-b", authd.reachableMembers.single().agentId)
                assertEquals("p2", authd.reachableMembers.single().homeProjectId)
                assertEquals(CrossAccess.READ, authd.reachableMembers.single().access)

                // DELETE revoke → immediately fail-closed.
                val revoked = repo.revoke("c1")
                assertFalse(revoked.shared)
                assertTrue(revoked.reachableMembers.isEmpty())

                // Operator gate: a repo without the operator token → 403 → mapped code (read still works).
                val ungated = HttpCrossProjectRepository(client, base, token = "", granteeProjects = { setOf("p2") })
                val gateErr = assertFails { ungated.authorize("c1") }
                assertIs<CrossProjectException>(gateErr)
                assertEquals("operator_required", gateErr.code)
                assertFalse(ungated.status("c1").shared) // GET (participant) still readable
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }
}
