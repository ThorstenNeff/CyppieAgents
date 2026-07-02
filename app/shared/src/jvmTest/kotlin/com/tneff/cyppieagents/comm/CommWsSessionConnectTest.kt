package com.tneff.cyppieagents.comm

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.ChannelsEvent
import com.tneff.cyppieagents.model.CommWsServerEvent
import com.tneff.cyppieagents.net.sharedWsHttpClient
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-188 P2b-ii verification — does P2a (session cred on the shared client) + B (`wsReaderOrNull` accepts
 * sessions on the read-WS) already close the live-view loop for a session-only user, with NO new client wiring?
 *
 * This drives the REAL [CommWsClient] over the REAL [sharedWsHttpClient] against a **faithful model** of B's
 * `wsReaderOrNull` (server-side `bearerToken() ?: ?token=` first — empty `Bearer `/`?token=` resolves to null —
 * then the `X-Session-Token` session). A session user has `token=""` (no operator token); the accept therefore
 * hinges ENTIRELY on P2a's DefaultRequest carrying `X-Session-Token` on the WS handshake. Positive: the app frame
 * arrives (accepted). Negative (no session cred): the socket is closed VIOLATED_POLICY → no frame. The negative
 * is the teeth — remove P2a's seam and the positive stops delivering a frame.
 */
class CommWsSessionConnectTest {

    /** Faithful [wsReaderOrNull] model: token (bearer|query) first, else the X-Session-Token session. */
    private fun withCommSocket(block: suspend (baseWsUrl: String) -> Unit) = runBlocking {
        val server = embeddedServer(Netty, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws/comm") {
                    val bearer = call.request.header("Authorization")
                        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                        ?.substring(7)?.trim()?.ifBlank { null }
                    val queryToken = call.request.queryParameters["token"]?.ifBlank { null }
                    val token = bearer ?: queryToken
                    val session = call.request.header("X-Session-Token")?.ifBlank { null }
                    val participant = when {
                        token == "op-token" -> "op"
                        session != null -> "session:$session" // wsReaderOrNull → resolvePrincipal → Human
                        else -> null
                    }
                    if (participant == null) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                        return@webSocket
                    }
                    // Accepted: deliver one app frame (the initial channels snapshot), then close cleanly.
                    send(Frame.Text(CommJson.encodeToString(CommWsServerEvent.serializer(), ChannelsEvent(emptyList()))))
                }
            }
        }.start()
        val port = server.engine.resolvedConnectors().first().port
        try {
            block("ws://127.0.0.1:$port")
        } finally {
            server.stop(100, 200)
        }
    }

    private suspend fun firstFrameArrives(events: Flow<CommLiveEvent>): Boolean = withTimeout(10_000) {
        try {
            var connected = false
            events.collect { e ->
                when (e) {
                    is CommLiveEvent.Connected -> connected = true
                    is CommLiveEvent.ChannelsChanged -> throw Accepted // app frame delivered ⇒ the gate accepted us
                    is CommLiveEvent.Disconnected -> if (connected) throw ClosedNoFrame
                    else -> Unit
                }
            }
            false
        } catch (e: Accepted) {
            true
        } catch (e: ClosedNoFrame) {
            false
        }
    }

    private object Accepted : Throwable()
    private object ClosedNoFrame : Throwable()

    @Test
    fun sessionUser_noOperatorToken_liveViewWsAccepted_viaP2aHandshakeCred() = withCommSocket { base ->
        // ⭐ token="" (session-only user). Accept hinges on P2a's X-Session-Token on the handshake. A frame arrives
        // ⇒ P2a + B close the live-view loop with NO extra client wiring. Drop the P2a seam → no frame → RED.
        val client = sharedWsHttpClient(sessionToken = { "sess-tok" })
        try {
            assertTrue(firstFrameArrives(CommWsClient(client, base, token = "").events()))
        } finally {
            client.close()
        }
    }

    @Test
    fun noCredential_liveViewWsRejected_soTheAcceptIsTheSessionCred() = withCommSocket { base ->
        // Teeth: no operator token AND no session → the gate closes VIOLATED_POLICY → no app frame. Proves the
        // positive above is earned by the session credential, not by the socket opening.
        val client = sharedWsHttpClient(sessionToken = { null })
        try {
            assertTrue(!firstFrameArrives(CommWsClient(client, base, token = "").events()))
        } finally {
            client.close()
        }
    }
}
