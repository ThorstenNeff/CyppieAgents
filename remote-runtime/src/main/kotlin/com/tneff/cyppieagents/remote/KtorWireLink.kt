package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.model.WireEnvelope
import com.tneff.cyppieagents.model.WireFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * CYP-142 (S4.3) — the production [WireLink] transport: a Ktor (CIO) WebSocket client to the hub's
 * `/ws/hub`. Auth = the agent's **S3-minted token** in the `Authorization: Bearer` header (the server's
 * auth-first `agentFor(token)` derives identity — no client-supplied agentId). Outbound frames are
 * wrapped in `WireEnvelope{v=1}` via [CommJson] and **serialized through one [sendLock]** (like the
 * server's RC1: two unsynchronized writers on one socket interleave). Inbound `WireEnvelope`s are
 * unwrapped to [WireFrame]s on [incoming]. Carries NO server secrets — only the agent token + the URL.
 */
class KtorWireLink(
    private val hubWsUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
) : WireLink {

    private val log = LoggerFactory.getLogger("remote.wire")
    private val client = HttpClient(CIO) { install(WebSockets) }
    private val sendLock = Mutex()
    private val _incoming = MutableSharedFlow<WireFrame>(extraBufferCapacity = 256)
    override val incoming: Flow<WireFrame> = _incoming

    @Volatile private var session: DefaultClientWebSocketSession? = null
    private var readerJob: Job? = null

    /** Open the WS session (Bearer-token auth) and start the inbound reader. Call before the relay starts. */
    suspend fun connect() {
        val s = client.webSocketSession(hubWsUrl) { header(HttpHeaders.Authorization, "Bearer $token") }
        session = s
        readerJob = scope.launch {
            try {
                for (frame in s.incoming) {
                    if (frame is Frame.Text) {
                        val env = runCatching { CommJson.decodeFromString<WireEnvelope>(frame.readText()) }.getOrNull()
                        if (env != null) _incoming.emit(env.frame)
                        else log.warn("dropping malformed wire frame")
                    }
                }
            } catch (e: Exception) {
                log.warn("wire reader ended: {}", e.message)
            }
        }
    }

    override suspend fun send(frame: WireFrame) = sendLock.withLock {
        val s = session ?: error("KtorWireLink: not connected")
        s.send(Frame.Text(CommJson.encodeToString(WireEnvelope.serializer(), WireEnvelope(1, frame))))
    }

    override suspend fun close() {
        readerJob?.cancel()
        runCatching { session?.close() }
        client.close()
    }
}
