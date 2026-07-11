package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthDeps
import com.tneff.cyppieagents.pty.PtyBusyException
import com.tneff.cyppieagents.pty.PtyManager
import com.tneff.cyppieagents.pty.PtySubscription
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * CYP-332 — `GET /ws/terminal?agentId=<id>` — the **PTY-over-WebSocket** transport (02 §8.1). The Desktop
 * JediTerm `TtyConnector` (CYP-331 Option D, Dev) binds to this: it sends [TerminalInput]/[TerminalResize],
 * receives [TerminalOutput]/[TerminalExit]. Bytes are Base64 in the frames (contract, [TerminalClientFrame]).
 *
 * **Auth (CYP-394 — WRITE-tier, operator-only, delegable):** `/ws/terminal` is a **bidirectional** PTY
 * (stdin + stdout of an interactive shell in the worktree = **code-exec in the repo**), so it is gated at
 * write-tier via [terminalPrincipalOrNull] + [mayOpenTerminal] over a [TerminalGrantStore] — NOT the read-tier
 * [wsReaderOrNull] (which admits a human MEMBER session and a participant token; both are read-only classes that
 * must not reach an interactive shell). Today the store is empty ([NoTerminalGrants]) → **operator-only**: an
 * operator token or verified OPERATOR session is admitted (store-independent), every other principal is
 * default-DENY (1008). A per-agent member-grant is the additive follow-up; when a grant is **revoked** while a
 * shell is open, the [TerminalGrantStore.track] kill-switch (registered on connect below) closes the live
 * session server-side (`grant_revoked`) — ACL-takes-effect-now, not just the next connect. Operator-gated
 * take-over / IDLE-gate / Seize are the CYP-333 hand-off follow-up (BE-1..3/5), not this through-line.
 *
 * **Attach-vs-spawn (CYP-381, Dev).** Two roles behind one route, chosen by whether the hand-off motor already
 * owns a live interactive PTY for this agent:
 *  - **Attach (motor owns it):** when the agent is in TERMINAL mode the motor spawned a `claude --resume` PTY via
 *    [PtyManager.spawnInteractive] with **no viewer**. This socket [PtyManager.attach]es as an **extra viewer** —
 *    replay the bounded scrollback, then stream live; **multi-viewer**. The process **outlives** the window, so a
 *    disconnect detaches ONLY this viewer, never the motor's session.
 *  - **Spawn (nothing live):** the CYP-333 interim — [PtyManager.open] spawns the `bash -l` worktree shell,
 *    WS-scoped (spawn on connect, teardown on disconnect). This is the **bash-fallback regression path**.
 *
 * The choice is a single atomic [PtyManager.attach] probe (a non-null return == live, no `isLive`-then-attach
 * TOCTOU). A genuine spawn-race (two connects both find nothing live and both [PtyManager.open]) is still rejected
 * by the per-agent single-flight → `1008 pty_busy` (never two rival PROCESSES; the process-level invariant is
 * covered by `PtyManagerTest`). Note a second connect to an ALREADY-live agent no longer 1008s — it attaches.
 *
 * **Ordering:** PTY stdout is drained on a pty4j IO thread; frames go through an **unbounded [Channel]** to a
 * single WS-side sender so terminal bytes are delivered **in order** (a `launch`-per-frame could reorder them).
 */
fun Route.terminalSocket(
    ptyManager: () -> PtyManager,
    knowsAgent: (agentId: String) -> Boolean,
    registry: TokenRegistry,
    deps: AuthDeps = AuthDeps(registry),
    // CYP-394: the per-agent terminal-access grant store. Production wires [NoTerminalGrants] (empty → operator-
    // only); a real store + operator-only grant endpoint are the additive member-delegation follow-up.
    grants: TerminalGrantStore = NoTerminalGrants,
) {
    webSocket("/ws/terminal") {
        // CYP-394: WRITE-tier gate (this socket is code-exec in the worktree, not a read). Resolve the principal,
        // then admit only an operator OR a per-agent-granted delegable principal (default-DENY). MEMBER-session
        // and participant-token resolve to a delegable subject → denied by the empty prod store.
        val principal = call.terminalPrincipalOrNull(deps, registry)
            ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
        val agentId = call.request.queryParameters["agentId"]
            ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "agentId required"))
        if (!mayOpenTerminal(agentId, principal, grants)) {
            return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "forbidden"))
        }
        if (!knowsAgent(agentId)) {
            return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown agent"))
        }

        val mgr = ptyManager()
        val b64 = Base64.getEncoder()
        // Unbounded so a burst of PTY output never blocks the IO thread and never drops bytes; a single
        // consumer preserves order.
        val outbound = Channel<TerminalServerFrame>(Channel.UNLIMITED)
        val onOutput: (ByteArray) -> Unit = { bytes -> outbound.trySend(TerminalOutput(b64.encodeToString(bytes))) }

        // CYP-381: atomically attach if the motor already owns a live PTY (replay + live, multi-viewer). A null
        // return means nothing is live → spawn the interim bash worktree-shell (the regression-guarded fallback).
        // CYP-391: pass an onExit so that if the motor's `claude --resume` process dies WHILE we're viewing, this
        // viewer gets a TerminalExit + a closed socket — mirroring the spawn path's onExit, not a frozen terminal.
        val viewer: PtySubscription? = mgr.attach(agentId, onOutput) { code ->
            outbound.trySend(TerminalExit(code)); outbound.close()
        }
        // ownsProcess = this socket spawned the PTY (bash interim) → it also tears it down on disconnect. A viewer
        // never owns the motor's session; detaching leaves it running for the motor and the other viewers.
        val ownsProcess = viewer == null
        if (ownsProcess) {
            try {
                mgr.open(
                    agentId = agentId,
                    cols = 80, rows = 24, // initial; the client's first Resize corrects it
                    onOutput = onOutput,
                    onExit = { code -> outbound.trySend(TerminalExit(code)); outbound.close() },
                )
            } catch (_: PtyBusyException) {
                // A motor/rival spawn raced in between attach() and open() → single-flight rejects us. Fail closed.
                return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pty_busy"))
            } catch (_: Exception) {
                return@webSocket close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "pty_spawn_failed"))
            }
        }

        // CYP-394: register this delegable session's server-side kill-switch so a grant REVOKE ends the live
        // shell immediately (ACL-takes-effect-now, not just the next connect). An operator bypasses grants and is
        // never tracked; [NoTerminalGrants.track] is a no-op (nothing to revoke). The watcher closes the socket on
        // a fired kill → the frame loop exits → the `finally` tears the PTY down.
        val killed = CompletableDeferred<Unit>()
        val tracking: AutoCloseable =
            if (principal is TerminalPrincipal.Delegable) grants.track(agentId, principal.subject) { killed.complete(Unit) }
            else AutoCloseable {}
        val revokeWatcher = launch {
            killed.await()
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "grant_revoked"))
        }

        val sender = launch {
            for (frame in outbound) send(Frame.Text(CommJson.encodeToString(TerminalServerFrame.serializer(), frame)))
            // outbound closed by onExit (spawn path) or disconnect (attach path) → close the socket after draining.
            close(CloseReason(CloseReason.Codes.NORMAL, "pty exited"))
        }

        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                // Input/resize address the live PTY by agentId — the SAME process whether we spawned it (bash) or
                // attached to the motor's session (so multiple viewers' keystrokes all reach the one process).
                when (val msg = runCatching { CommJson.decodeFromString<TerminalClientFrame>(frame.readText()) }.getOrNull()) {
                    is TerminalInput -> mgr.write(agentId, Base64.getDecoder().decode(msg.dataBase64))
                    is TerminalResize -> mgr.resize(agentId, msg.cols, msg.rows)
                    null -> {} // version drift / garbage: skip, don't kill the session
                }
            }
        } finally {
            revokeWatcher.cancel()
            tracking.close() // CYP-394: deregister the kill-switch so a later revoke never fires a stale kill
            if (ownsProcess) {
                // Bash interim: WS-scoped lifecycle — tear the PTY down on disconnect.
                mgr.close(agentId)
            } else {
                // Viewer of the motor-owned session: detach ONLY this viewer; the motor owns teardown at hand-back.
                viewer?.close()
            }
            outbound.close()
            sender.cancel()
        }
    }
}
