package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.pty.PtyBusyException
import com.tneff.cyppieagents.pty.PtyManager
import com.tneff.cyppieagents.model.TerminalClientFrame
import com.tneff.cyppieagents.model.TerminalExit
import com.tneff.cyppieagents.model.TerminalInput
import com.tneff.cyppieagents.model.TerminalOutput
import com.tneff.cyppieagents.model.TerminalResize
import com.tneff.cyppieagents.model.TerminalServerFrame
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * CYP-332 — `GET /ws/terminal?agentId=<id>` — the **PTY-over-WebSocket** transport (02 §8.1). The Desktop
 * JediTerm `TtyConnector` (CYP-331 Option D, Dev) binds to this: it sends [TerminalInput]/[TerminalResize],
 * receives [TerminalOutput]/[TerminalExit]. Bytes are Base64 in the frames (contract, [TerminalClientFrame]).
 *
 * **Auth:** admits any valid reader (agent/operator token or verified session), same bar as `/ws/agent`
 * ([wsReaderOrNull]); operator-gated take-over / IDLE-gate / Seize are the CYP-333 hand-off follow-up
 * (BE-1..3/5), not this through-line.
 *
 * **Single-flight (§4.1):** [PtyManager.open] rejects a second live PTY for the same agent → the socket
 * closes `1008 pty_busy` (never two rival interactive processes on one session). **Lifecycle:** this story
 * scopes the PTY to the socket — spawn on connect, teardown on disconnect; a PTY that survives reconnect is
 * a follow-up.
 *
 * **Ordering:** PTY stdout is drained on a pty4j IO thread; frames go through an **unbounded [Channel]** to a
 * single WS-side sender so terminal bytes are delivered **in order** (a `launch`-per-frame could reorder them).
 */
fun Route.terminalSocket(
    ptyManager: () -> PtyManager,
    knowsAgent: (agentId: String) -> Boolean,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
) {
    webSocket("/ws/terminal") {
        val reader = call.wsReaderOrNull(deps, registry)
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        val agentId = call.request.queryParameters["agentId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "agentId required"))
        if (!knowsAgent(agentId)) {
            return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown agent"))
        }

        val mgr = ptyManager()
        val b64 = Base64.getEncoder()
        // Unbounded so a burst of PTY output never blocks the IO thread and never drops bytes; a single
        // consumer preserves order.
        val outbound = Channel<TerminalServerFrame>(Channel.UNLIMITED)

        val handle = try {
            mgr.open(
                agentId = agentId,
                cols = 80, rows = 24, // initial; the client's first Resize corrects it
                onOutput = { bytes -> outbound.trySend(TerminalOutput(b64.encodeToString(bytes))) },
                onExit = { code -> outbound.trySend(TerminalExit(code)); outbound.close() },
            )
        } catch (_: PtyBusyException) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pty_busy"))
        } catch (_: Exception) {
            return@webSocket close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "pty_spawn_failed"))
        }

        val sender = launch {
            for (frame in outbound) send(Frame.Text(CommJson.encodeToString(TerminalServerFrame.serializer(), frame)))
            // outbound closed by onExit → the process is gone → close the socket after the final Exit frame.
            close(CloseReason(CloseReason.Codes.NORMAL, "pty exited"))
        }

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                when (val msg = runCatching { CommJson.decodeFromString<TerminalClientFrame>(frame.readText()) }.getOrNull()) {
                    is TerminalInput -> handle.write(Base64.getDecoder().decode(msg.dataBase64))
                    is TerminalResize -> handle.resize(msg.cols, msg.rows)
                    null -> {} // version drift / garbage: skip, don't kill the session
                }
            }
        } finally {
            // WS disconnect → tear the PTY down (this story's WS-scoped lifecycle) and stop the sender.
            mgr.close(agentId)
            outbound.close()
            sender.cancel()
        }
    }
}
