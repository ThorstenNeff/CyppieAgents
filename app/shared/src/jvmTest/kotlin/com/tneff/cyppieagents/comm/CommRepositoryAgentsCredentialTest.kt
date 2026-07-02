package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.model.Agent
import com.tneff.cyppieagents.model.Channel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CC1 / CYP-179 — the PO-named surface: [CommRepository.agents] must call `GET /api/agents` with the **same
 * credential** it already sends for `GET /api/channels` (both go through the one bearer-carrying `getList`).
 * The gate (`requireCommReader`) 401s an anonymous read, so this pins that `agents()` is NOT anonymous — a
 * mutation that drops the header on the agents path 401s → [CommHttpException] → the agents assert goes RED,
 * while `channels()` (unchanged) stays green. Guards against ever regressing `agents()` to an anonymous fetch.
 */
class CommRepositoryAgentsCredentialTest {

    private fun withServer(block: suspend (CommRepository) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                // Both reads are gated identically: 401 without the exact bearer, else the (empty) list.
                get("/api/agents") { call.gated { call.respondText("[]", ContentType.Application.Json) } }
                get("/api/channels") { call.gated { call.respondText("[]", ContentType.Application.Json) } }
            }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO)
        try {
            block(CommRepository(client, "http://127.0.0.1:$port", "op"))
        } finally {
            client.close()
            server.stop()
        }
    }

    @Test
    fun agents_sendsSameCredentialAsChannels() = withServer { repo ->
        // Neither throws ⇒ both carried "Bearer op" past the gate. (Empty lists; the point is the credential.)
        assertEquals(emptyList<Agent>(), repo.agents())
        assertEquals(emptyList<Channel>(), repo.channels())
    }
}

/** Respond via [served] only when the operator bearer is present; otherwise 401 (models `requireCommReader`). */
private suspend fun io.ktor.server.application.ApplicationCall.gated(served: suspend () -> Unit) {
    if (request.header(HttpHeaders.Authorization) != "Bearer op") {
        respondText("unauthorized", status = HttpStatusCode.Unauthorized)
    } else {
        served()
    }
}
