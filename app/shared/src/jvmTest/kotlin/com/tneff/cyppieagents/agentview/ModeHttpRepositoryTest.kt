package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.AgentTerminalControlEvent
import com.tneff.cyppieagents.model.ModeChangeOutcome
import com.tneff.cyppieagents.model.ModeChangeRejection
import com.tneff.cyppieagents.model.ModeChangeRequest
import com.tneff.cyppieagents.model.ModeChangeResponse
import com.tneff.cyppieagents.model.TerminalControlState
import com.tneff.cyppieagents.model.TerminalMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * CYP-381 (Dev) — the live [ModeHttpRepository] real-seam against CYP-355's frozen `:core` DTOs (no hand-parse).
 * Proves the **non-optimistic** contract mapping: a CONFIRMED body → flip to the requested target (+ holder/since
 * echoed from the shared CYP-354 control shape); a REJECTED **200** body → a [ModeChangeException] with the mapped
 * reason (so the VM stays in the old mode); a structural 404 → `agent_not_found`. Also pins the request-body
 * `AgentContentMode → TerminalMode` mapping (the one bit the client sends).
 */
class ModeHttpRepositoryTest {

    private fun withModeServer(route: Route.() -> Unit, block: suspend (ModeHttpRepository) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, port = 0) { routing { route() } }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                block(ModeHttpRepository(client, "http://127.0.0.1:$port", token = "op"))
            } finally {
                client.close()
            }
        } finally {
            server.stop(100, 200)
        }
    }

    @Test
    fun confirmed_flipsToTarget_echoesHolderSince_andSendsTargetInBody() {
        val received = AtomicReference<TerminalMode?>(null)
        withModeServer({
            post("/api/agents/{id}/mode") {
                val req = CommJson.decodeFromString(ModeChangeRequest.serializer(), call.receiveText())
                received.set(req.target)
                val resp = ModeChangeResponse(
                    outcome = ModeChangeOutcome.CONFIRMED,
                    control = AgentTerminalControlEvent("backend", TerminalControlState.INTERACTIVE, heldBy = "alice", since = 1234L),
                )
                call.respondText(CommJson.encodeToString(ModeChangeResponse.serializer(), resp), ContentType.Application.Json)
            }
        }) { repo ->
            val confirm = repo.setMode("backend", AgentContentMode.TERMINAL)
            assertEquals(AgentContentMode.TERMINAL, confirm.confirmed, "flips to the requested target ONLY on CONFIRMED")
            assertEquals("alice", confirm.heldBy, "echoes the CYP-354 holder from the shared control shape")
            assertEquals(1234L, confirm.since, "echoes the since instant")
            assertEquals(TerminalMode.TERMINAL, received.get(), "AgentContentMode.TERMINAL maps to the TerminalMode.TERMINAL request pole")
        }
    }

    @Test
    fun rejected200_throwsMappedReason_soTheViewStaysInTheOldMode() {
        withModeServer({
            post("/api/agents/{id}/mode") {
                val resp = ModeChangeResponse(
                    outcome = ModeChangeOutcome.REJECTED,
                    control = AgentTerminalControlEvent("backend", TerminalControlState.MEDIATED), // unchanged prior mode
                    reason = ModeChangeRejection.BUSY_TIMEOUT,
                )
                call.respondText(CommJson.encodeToString(ModeChangeResponse.serializer(), resp), ContentType.Application.Json)
            }
        }) { repo ->
            val ex = assertFailsWith<ModeChangeException> { repo.setMode("backend", AgentContentMode.TERMINAL) }
            assertEquals("agent_busy", ex.code, "BUSY_TIMEOUT (a 200 REJECTED body) maps to agent_busy — the caller must NOT flip")
        }
    }

    @Test
    fun structural404_throwsAgentNotFound() {
        withModeServer({
            post("/api/agents/{id}/mode") { call.respond(HttpStatusCode.NotFound, "") }
        }) { repo ->
            val ex = assertFailsWith<ModeChangeException> { repo.setMode("ghost", AgentContentMode.ORCHESTRATION) }
            assertEquals("agent_not_found", ex.code, "an unknown agent (404) fails closed, never a silent success")
        }
    }
}
