package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.controlplane.HubTicketFailure
import com.tneff.cyppieagents.controlplane.HubTicketRequest
import com.tneff.cyppieagents.controlplane.HubTicketResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CYP-496 e2e — [HttpCpJwtProvider] against an embedded CP mint: it POSTs `HubTicketRequest{hubId, cb}` (cb from
 * the injected [ChannelBinding] seam) with the operator **session** as Bearer, and returns the minted `cpJwt`; a
 * typed failure (`CP_SESSION_EXPIRED` / `NOT_AUTHORIZED_FOR_HUB`), a non-200, or no session all **fail closed**
 * to `null` (never an invented ticket).
 */
class Cyp496CpJwtProviderE2eTest {

    private val h = ByteArray(32) { 0x11 }

    // fake channel-binding — the byte-critical real cb is the shared :core helper (CYP-514); here it's deterministic.
    private val fakeCb = ChannelBinding { hh, hub -> "cb(${hh.size}:$hub)" }

    private class CpStub(val response: HubTicketResponse?, val status: HttpStatusCode = HttpStatusCode.OK) {
        var authHeader: String? = null
        var seenRequest: HubTicketRequest? = null
    }

    private suspend fun withCp(stub: CpStub, block: suspend (String) -> Unit) {
        val server = embeddedServer(Netty, port = 0) {
            routing {
                post("/api/cp/hubticket") {
                    stub.authHeader = call.request.headers["Authorization"]
                    stub.seenRequest = CommJson.decodeFromString(HubTicketRequest.serializer(), call.receiveText())
                    val r = stub.response
                    if (r == null) call.respondText("forbidden", status = stub.status)
                    else call.respondText(CommJson.encodeToString(HubTicketResponse.serializer(), r), status = stub.status)
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

    private fun provider(cpBaseUrl: String, client: HttpClient, token: String? = "sess-op") =
        HttpCpJwtProvider(client, cpBaseUrl, operatorToken = { token }, channelBinding = fakeCb)

    @Test
    fun success_sendsHubIdAndCb_withSessionBearer_returnsTicket() = runBlocking {
        val stub = CpStub(HubTicketResponse(cpJwt = "hubticket-xyz"))
        withCp(stub) { url ->
            val client = HttpClient(CIO)
            try {
                assertEquals("hubticket-xyz", provider(url, client).cpJwt(h, "hub-a"))
                assertEquals("Bearer sess-op", stub.authHeader, "the operator session travels as Bearer")
                assertEquals("hub-a", stub.seenRequest?.hubId)
                assertEquals("cb(32:hub-a)", stub.seenRequest?.cb, "the cb from the injected ChannelBinding is sent")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun cpSessionExpired_failsClosed_null() = runBlocking {
        withCp(CpStub(HubTicketResponse(failure = HubTicketFailure.CP_SESSION_EXPIRED))) { url ->
            val client = HttpClient(CIO)
            try {
                assertNull(provider(url, client).cpJwt(h, "hub-a"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun notAuthorizedForHub_failsClosed_null() = runBlocking {
        withCp(CpStub(HubTicketResponse(failure = HubTicketFailure.NOT_AUTHORIZED_FOR_HUB))) { url ->
            val client = HttpClient(CIO)
            try {
                assertNull(provider(url, client).cpJwt(h, "hub-a"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun non200_failsClosed_null() = runBlocking {
        withCp(CpStub(response = null, status = HttpStatusCode.Forbidden)) { url ->
            val client = HttpClient(CIO)
            try {
                assertNull(provider(url, client).cpJwt(h, "hub-a"))
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun noOperatorSession_failsClosed_null() = runBlocking {
        withCp(CpStub(HubTicketResponse(cpJwt = "unreached"))) { url ->
            val client = HttpClient(CIO)
            try {
                assertNull(provider(url, client, token = null).cpJwt(h, "hub-a"), "no session ⇒ null (no anonymous mint)")
            } finally {
                client.close()
            }
        }
    }
}
