package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.CommJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.tneff.cyppieagents.model.HubDescriptor as CoreHubDescriptor

/**
 * S-J e2e — [HttpControlPlaneClient] against an embedded CP serving `GET /api/cp/hubs`: it sends the operator
 * **session** as Bearer, decodes the `:core` [CoreHubDescriptor] list, and maps it **straight-through** (incl. the
 * TOFU-load-bearing `dhPubKey`) to the client-facing [HubDescriptor]. Fail-closed ([ControlPlaneUnreachableException])
 * on no session, a non-200, or a transport error — never a hang, never a fabricated "online" hub. `registerHub` is
 * vestigial (list+select model) and fails closed, never fabricating a hub.
 */
class HttpControlPlaneClientE2eTest {

    private class CpHubsStub(val hubs: List<CoreHubDescriptor>, val status: HttpStatusCode = HttpStatusCode.OK) {
        var authHeader: String? = null
        var calls = 0
    }

    private suspend fun withCp(stub: CpHubsStub, block: suspend (String) -> Unit) {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                get("/api/cp/hubs") {
                    stub.calls++
                    stub.authHeader = call.request.headers["Authorization"]
                    if (stub.status != HttpStatusCode.OK) {
                        call.respondText("nope", status = stub.status)
                    } else {
                        call.respondText(
                            CommJson.encodeToString(ListSerializer(CoreHubDescriptor.serializer()), stub.hubs),
                            status = HttpStatusCode.OK,
                        )
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            block("http://127.0.0.1:${server.engine.resolvedConnectors().first().port}")
        } finally {
            server.stop()
        }
    }

    private fun client(cpBaseUrl: String, http: HttpClient, token: String? = "sess-op") =
        HttpControlPlaneClient(http, cpBaseUrl, operatorToken = { token })

    private val coreHub = CoreHubDescriptor(
        hubId = "hub-a", name = "Staging Hub", online = true, defaultPort = 8787,
        lastSeen = 1_720_000_000_000L, dhPubKey = "QUJDRA==", // base64 — the load-bearing TOFU key
    )

    @Test
    fun hubs_decodesAndMapsStraightThrough_withSessionBearer() = runBlocking<Unit> {
        val stub = CpHubsStub(listOf(coreHub))
        withCp(stub) { url ->
            val http = HttpClient(CIO)
            try {
                val hubs = client(url, http).hubs()
                assertEquals(1, hubs.size)
                val h = hubs.first()
                // 1:1 straight-through map — every field, incl. the dhPubKey the client TOFU-pins.
                assertEquals("hub-a", h.hubId)
                assertEquals("Staging Hub", h.name)
                assertEquals(true, h.online)
                assertEquals(8787, h.defaultPort)
                assertEquals(1_720_000_000_000L, h.lastSeen)
                assertEquals("QUJDRA==", h.dhPubKey, "dhPubKey must map straight through (TOFU pin source)")
                assertEquals("Bearer sess-op", stub.authHeader, "the operator session travels as Bearer")
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun hubs_nonOk_failsClosed_unreachable() = runBlocking<Unit> {
        val stub = CpHubsStub(emptyList(), status = HttpStatusCode.Unauthorized)
        withCp(stub) { url ->
            val http = HttpClient(CIO)
            try {
                assertFailsWith<ControlPlaneUnreachableException> { client(url, http).hubs() }
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun hubs_noOperatorSession_failsClosed_neverCallsCp() = runBlocking<Unit> {
        val stub = CpHubsStub(listOf(coreHub))
        withCp(stub) { url ->
            val http = HttpClient(CIO)
            try {
                assertFailsWith<ControlPlaneUnreachableException> { client(url, http, token = null).hubs() }
                assertEquals(0, stub.calls, "no session ⇒ fail closed BEFORE any CP call (no anonymous hub-list leak)")
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun hubs_transportError_failsClosed() = runBlocking<Unit> {
        // Point at a refused port (nothing listening) — a transport error must surface as unreachable, never hang.
        val http = HttpClient(CIO)
        try {
            assertFailsWith<ControlPlaneUnreachableException> { client("http://127.0.0.1:1", http).hubs() }
        } finally {
            http.close()
        }
    }

    @Test
    fun registerHub_isVestigial_failsClosed_neverFabricatesHub() = runBlocking<Unit> {
        val http = HttpClient(CIO)
        try {
            // list+select model: register is vestigial (hubs self-admit). It must fail closed, never return a hub.
            assertFailsWith<ControlPlaneUnreachableException> { client("http://127.0.0.1:1", http).registerHub("x") }
            assertTrue(true)
        } finally {
            http.close()
        }
    }
}
