package com.tneff.cyppieagents.agentview

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.comm.ConnectionStatus
import com.tneff.cyppieagents.model.StoredAgentEvent
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.net.Backoff
import com.tneff.cyppieagents.net.isAccessRevoked
import com.tneff.cyppieagents.net.logWsTeardown
import com.tneff.cyppieagents.net.readCloseCode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
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
 * **CYP-335:** [events] surfaces the whole [StoredAgentEvent] envelope (not just its inner [StreamJsonEvent])
 * so the server-stamped `tsMs` reaches the transcript renderer.
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
    /** CYP-600/CYP-598-B — the reconnect wait, injectable so a test observes the backoff `attempt` sequence WITHOUT
     *  real time. Default = the exponential [backoff] delay. The point of the seam is the attempt ESCALATION: a
     *  connection that never delivers a frame (a stopped agent the server accept-then-closes) must NOT reset the
     *  ladder, so it backs off to the cap instead of hammering the shared client runtime at the floor. */
    private val reconnectDelay: suspend (attempt: Int) -> Unit = { delay(backoff.delayFor(it)) },
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
    val events: Flow<StoredAgentEvent> = channelFlow {
        var attempt = 0
        while (true) {
            var endReason = "incoming-closed" // the server/tunnel closed the WS cleanly (the for(incoming) loop ended)
            // CYP-821: set iff the server closed with 1008 (VIOLATED_POLICY) — a revoked/invalid token. TERMINAL.
            var revoked = false
            try {
                client.webSocket(
                    urlString = agentUrl(),
                    // CYP-230: only send a Bearer when we actually have a token (native/agent-self/break-glass
                    // operator). The deployed SPA sends none → the server authenticates via the handshake session cookie.
                    request = { if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token") },
                ) {
                    _connection.value = ConnectionStatus.LIVE
                    // CYP-600/CYP-598-B: do NOT reset the backoff ladder on the bare WS UPGRADE. A stopped agent makes
                    // the server accept-then-immediately-close (0 frames); resetting here kept `attempt` pinned at the
                    // 250ms floor → an eternal ~250ms reconnect HAMMER (311k live) that hogs the shared client runtime
                    // (Ktor engine / Dispatchers.IO) and starves the idle agents' sockets. The reset now happens only
                    // on a PRODUCTIVE frame (below) → a 0-frame connection escalates to the backoff cap (~5s poll).
                    val pump = launch {
                        for (turn in outbound) send(Frame.Text(CommJson.encodeToString(UserTurn.serializer(), turn)))
                    }
                    try {
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    attempt = 0 // productive connection: a real frame arrived → the ladder is safe to reset
                                    val stored = CommJson.decodeFromString(StoredAgentEvent.serializer(), frame.readText())
                                    if (stored.seq > lastSeq) { // dedup + advance cursor (idempotent replay)
                                        lastSeq = stored.seq
                                        // CYP-335: forward the WHOLE envelope, not just `.event` — the server's `tsMs`
                                        // is the transcript's only honest clock. Stamping at render time would re-date
                                        // replayed history on every reconnect.
                                        this@channelFlow.send(stored)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            // CYP-821: Ktor tears a closing socket down by cancelling the frame channel. If a close
                            // reason is already recorded, this IS that teardown (e.g. a 1008 reject) — NOT a real
                            // collector cancel — so swallow it and read the code below. A genuine cancel propagates.
                            if (!closeReason.isCompleted) throw e
                        }
                        // CYP-821: a 1008 (VIOLATED_POLICY) auth-revoke is TERMINAL — like the four status feeds
                        // (CYP-819) and comm/acl/events, do NOT re-dial the dead token forever (the CYP-289 hammer
                        // class). readCloseCode runs under NonCancellable so a 1008 is seen even as the socket tears
                        // down. The workspace-wide revoke UX is CYP-819's job (statusRevoked); here we only stop the
                        // background hammer + hold the connection at DISCONNECTED (honest, terminal).
                        if (isAccessRevoked(readCloseCode())) revoked = true
                    } finally {
                        pump.cancel()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                endReason = "exception:${t::class.simpleName}"
                // Transient socket/connect error → reconnect from the cursor after a backoff.
            }
            _connection.value = ConnectionStatus.DISCONNECTED
            // Tunnel-warmth incident instrumentation: log WHY this agent WS ended so an instrumented re-test can
            // correlate whether many agent WS end synchronously (a batch teardown) and their cause. No secrets —
            // agentId + reason + attempt only (never tokens/handshake material).
            if (revoked) {
                // CYP-821: terminal — stop the reconnect loop so the revoked token is never re-dialed (the
                // safe-but-silent CYP-289 hammer the four status feeds already close in CYP-819). Completing the
                // channelFlow ends `events`; the connection stays DISCONNECTED (never a false "reconnecting").
                logWsTeardown("agent-ws:$agentId", "1008 revoke → terminal (no reconnect)")
                break
            }
            logWsTeardown("agent-ws:$agentId", "$endReason → reconnect #${attempt + 1}")
            attempt += 1
            reconnectDelay(attempt) // CYP-598-B: escalates for a 0-frame (stopped-agent) connection — no floor-hammer
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
        // CYP-230: send `?token=` ONLY when a token is present; the deployed SPA sends none → cookie auth. No blank/
        // guessable token ever leaves the client. id + token URL-encoded (defense: no `&`/scheme/param injection).
        val tokenParam = if (token.isNotBlank()) "&token=${token.encodeURLParameter()}" else ""
        return "$baseUrl${sep}ws/agent?agentId=${agentId.encodeURLParameter()}$tokenParam$since"
    }
}
