package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.net.Backoff
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Ktor WebSocket adapter for the per-agent event stream (CYP-1/CYP-13; persistence + reconnect: CYP-204).
 *   - **Endpoint:** `GET {baseUrl}/ws/agent?agentId=<id>[&since=<seq>]`, Bearer auth at the upgrade. Browser
 *     fallback: `?token=<t>` query param (browser WebSocket can't set request headers).
 *   - **Server→Client:** one WS text frame == one [StoredAgentEvent] (CYP-198): `{seq, agentId, projectId,
 *     tsMs, event}` where `event` is an ALREADY-masked [StreamJsonEvent] (Gate #3). `seq` is a gapless,
 *     monotonic, restart-surviving cursor; the server guarantees gapless + dedup on `?since`.
 *   - **Client→Server:** one frame == a [UserTurn] `{"text":"…"}`; the mediator injects it on stdin (Gate #5).
 *
 * **CYP-204 reconnect (the CYP-115 follow-up):** [events] auto-reconnects. On the first connect `since` is
 * omitted → the server replays the WHOLE persisted history (window shows the transcript, not blank). On every
 * reconnect it resumes from [lastSeq] via `?since=<lastSeq>` → replay-then-live with no gap, and it drops any
 * frame with `seq <= lastSeq` so a replay is idempotent (no dups) even if the server re-sends the cursor event.
 * [connection] surfaces the live socket state for the window's reconnecting indicator.
 *
 * Pair with [MappingAgentSession]:
 * ```
 * val ws = AgentWsClient(client, baseUrl, agentId, token)
 * val session = MappingAgentSession(source = ws.events, sink = ws::send, connection = ws.connection)
 * ```
 */
class AgentWsClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val agentId: String,
    private val token: String,
    private val backoff: Backoff = Backoff(),
) {
    private val outbound = Channel<UserTurn>(Channel.BUFFERED)

    // CYP-204: the last StoredAgentEvent.seq surfaced — the gapless, restart-surviving reconnect cursor. Read
    // on every (re)connect for `?since=<lastSeq>`, and used to dedup (drop seq <= lastSeq). A single window is
    // the only collector and the read/write happens on the WS dispatcher, so a plain var is safe here.
    private var lastSeq: Long = -1L

    private val _connection = MutableStateFlow(ConnectionStatus.CONNECTING)
    /** CYP-204: live socket state for the window's reconnecting indicator (CONNECTING → LIVE → DISCONNECTED↻). */
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    /**
     * Cold, **auto-reconnecting** stream of masked events. channelFlow (not flow): the webSocket body runs on
     * the engine dispatcher, so emitting is a cross-context send — legal in channelFlow (the ISE behind the
     * CYP-115 churn is exactly what flow{} forbids). On any drop it waits an exponential [backoff] (status
     * DISCONNECTED), then re-opens from [lastSeq]. Cancellation propagates and ends the loop (never swallowed).
     */
    val events: Flow<StreamJsonEvent> = channelFlow {
        var attempt = 0
        while (true) {
            try {
                client.webSocket(
                    urlString = agentUrl(),
                    request = { header(HttpHeaders.Authorization, "Bearer $token") },
                ) {
                    _connection.value = ConnectionStatus.LIVE
                    attempt = 0 // a successful connect resets the backoff ladder
                    val pump = launch {
                        for (turn in outbound) send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), turn)))
                    }
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val stored = CommJson.decodeFromString(StoredAgentEvent.serializer(), frame.readText())
                                if (stored.seq > lastSeq) { // dedup + advance cursor (idempotent replay)
                                    lastSeq = stored.seq
                                    this@channelFlow.send(stored.event)
                                }
                            }
                        }
                    } finally {
                        pump.cancel()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // Transient socket/connect error → reconnect from the cursor after a backoff.
            }
            _connection.value = ConnectionStatus.DISCONNECTED
            attempt += 1
            delay(backoff.delayFor(attempt))
        }
    }

    /** Queue a human turn for the active socket (Hub-mediated; the client never writes stdin). */
    fun send(turn: UserTurn) {
        outbound.trySend(turn)
    }

    private fun agentUrl(): String {
        val sep = if (baseUrl.endsWith("/")) "" else "/"
        // Omit `since` on the first connect (lastSeq < 0) → the server replays the whole history; on reconnect
        // resume from the cursor. Auth via `?token=` (browser WS can't set the Authorization header).
        val since = if (lastSeq >= 0L) "&since=$lastSeq" else ""
        return "$baseUrl${sep}ws/agent?agentId=$agentId&token=$token$since"
    }
}
