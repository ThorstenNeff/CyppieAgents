package com.tneff.cyppieagents.routing

import com.tneff.cyppieagents.connector.ConnectorSession
import com.tneff.cyppieagents.model.StreamJsonEvent
import com.tneff.cyppieagents.model.UserTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * CYP-141 / E2.5 — a [ConnectorSession] backed by a remote connector's `/ws/hub` connection. The
 * CYP-132 [com.tneff.cyppieagents.mediation.MessageDeliverer] delivers inbound to it **exactly as to a
 * local session**, via [sendTurn]; here that serializes a `WireDeliver` frame pushed over the WS. The
 * deliverer is unchanged — Remote-Accept adds this session impl, not a new delivery path.
 *
 *  - **RC1:** [sendDeliver] MUST route through the connection's single shared send-lock (the same lock the
 *    read-loop's `reply()` uses) — two writers on one WS would otherwise interleave frames /
 *    `ClosedSendChannelException`.
 *  - **RC2:** [sendTurn] **propagates** any send failure (closed WS / network) — it does NOT swallow it.
 *    The deliverer marks a message delivered only AFTER a successful `sendTurn`, so a throwing wire send
 *    leaves the cursor un-advanced → the message is re-delivered on reconnect (a wire `sendTurn`, unlike a
 *    local one, can throw; CYP-132 was built assuming it never does — this preserves at-least-once).
 */
class WireConnectorSession(
    override val agentId: String,
    private val sendDeliver: suspend (String) -> Unit,
    private val closeWs: () -> Unit,
) : ConnectorSession {
    // MVP: the server does not receive the remote's raw process stream (it self-reports via WireSend); a
    // self-reported event channel for Scanner/Warden is a named E2.6 deferral.
    override val events: Flow<StreamJsonEvent> = emptyFlow()

    override suspend fun sendTurn(turn: UserTurn) = sendDeliver(turn.text) // RC2: throw-on-closed propagates

    override fun close() = closeWs()
}
