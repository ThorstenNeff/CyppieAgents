package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.controlplane.RendezvousBinding
import com.tneff.cyppieagents.controlplane.RendezvousResolveResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * CYP-494 e2e — [HttpRendezvousResolver] against an embedded CP: it hits `GET /api/cp/rendezvous/{hubId}` with the
 * operator bearer, decodes the `:core` typed 200 body, and — the fail-closed property — a non-200 (structural
 * failure) THROWS (→ the dialer surfaces RelayUnreachable), never a fabricated Bound.
 */
class Cyp494HttpRendezvousResolverE2eTest {

    @Test
    fun resolve_getsTypedBinding_withOperatorBearer_atTheRightPath() = runBlocking {
        var authHeader: String? = null
        var hubIdPath: String? = null
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/cp/rendezvous/{hubId}") {
                    authHeader = call.request.headers["Authorization"]
                    hubIdPath = call.parameters["hubId"]
                    call.respondText(
                        CommJson.encodeToString(
                            RendezvousResolveResponse.serializer(),
                            RendezvousResolveResponse(binding = RendezvousBinding("rzv-9", "ws://relay/z")),
                        ),
                    )
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val resolver = HttpRendezvousResolver(client, "http://127.0.0.1:$port") { "op-token" }
                val bound = assertIs<RendezvousResolution.Bound>(resolver.resolve("hub-a"))
                assertEquals("rzv-9", bound.rendezvousId)
                assertEquals("ws://relay/z", bound.relayUrl)
                assertEquals("Bearer op-token", authHeader, "operator-gated: the bearer is sent")
                assertEquals("hub-a", hubIdPath, "hubId is the path param of /api/cp/rendezvous/{hubId}")
            } finally {
                client.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun resolve_non200_failsClosed_throws() = runBlocking<Unit> {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/cp/rendezvous/{hubId}") {
                    call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                }
            }
        }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = HttpClient(CIO)
            try {
                val resolver = HttpRendezvousResolver(client, "http://127.0.0.1:$port") { "op-token" }
                assertFailsWith<Exception> { resolver.resolve("hub-a") } // structural 403 → fail-closed throw
            } finally {
                client.close()
            }
        } finally {
            server.stop()
        }
    }
}
