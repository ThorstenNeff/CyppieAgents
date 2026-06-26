package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Ktor WebSocket adapter for the per-agent event stream — implements the frame contract frozen with
 * Backend (CYP-1/CYP-13):
 *   - **Endpoint:** `GET {baseUrl}/ws/agent?agentId=<id>`, Bearer auth at the upgrade. Browser
 *     fallback: `?token=<t>` query param (browser WebSocket can't set request headers).
 *   - **Server→Client:** one WS text frame == one **already-masked** [StreamJsonEvent]
 *     (Gate #3 masking is server-side); decode each frame 1:1 via [CommJson].
 *   - **Client→Server:** one frame == a [UserTurn] serialized as `{"text":"…"}`; the server injects
 *     it on the CLI stdin through its single-flight turn queue (Gate #5).
 *
 * [HttpClient] (with the WebSockets plugin + a platform engine) is injected, so this stays in
 * commonMain and the engine choice is the caller's. Pair with [MappingAgentSession]:
 * ```
 * val ws = AgentWsClient(client, baseUrl, agentId, token)
 * val session = MappingAgentSession(source = ws.events, sink = ws::send)
 * ```
 *
 * NOTE: the live route exists once Backend ships CYP-13; this adapter is built against the contract
 * so the two plug together e2e then. The frame codec is unit-tested ([AgentWsCodecTest]); the socket
 * lifecycle is verified when the route lands.
 */
class AgentWsClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val agentId: String,
    private val token: String,
) {
    private val outbound = Channel<UserTurn>(Channel.BUFFERED)

    /** Cold stream: opens the WS on collection, decodes each masked frame, closes on cancel. */
    val events: Flow<StreamJsonEvent> = flow {
        client.webSocket(
            urlString = agentUrl(),
            request = { header(HttpHeaders.Authorization, "Bearer $token") },
        ) {
            val pump = launch {
                for (turn in outbound) {
                    send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), turn)))
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        emit(CommJson.decodeFromString(StreamJsonEvent.serializer(), frame.readText()))
                    }
                }
            } finally {
                pump.cancel()
            }
        }
    }

    /** Queue a human turn for the active socket (Hub-mediated; the client never writes stdin). */
    fun send(turn: UserTurn) {
        outbound.trySend(turn)
    }

    private fun agentUrl(): String {
        val sep = if (baseUrl.endsWith("/")) "" else "/"
        return "$baseUrl${sep}ws/agent?agentId=$agentId&token=$token"
    }
}
