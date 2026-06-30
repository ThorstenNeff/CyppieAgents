package com.tneff.cyppieagents.remote

import com.tneff.cyppieagents.connector.AgentProcess
import com.tneff.cyppieagents.connector.ClaudeCodeSession
import com.tneff.cyppieagents.connector.MediationGate
import com.tneff.cyppieagents.mediation.SessionTurnQueue
import com.tneff.cyppieagents.model.Capabilities
import com.tneff.cyppieagents.model.ProviderInfo
import com.tneff.cyppieagents.model.UserTurn
import com.tneff.cyppieagents.model.WireDeliver
import com.tneff.cyppieagents.model.WireHello
import com.tneff.cyppieagents.model.WireSend
import com.tneff.cyppieagents.model.WireSubscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * CYP-142 (S4.1) — the Remote Bridge relay loop. Runs in USER infra: wraps the user's Claude-Code
 * stream-json session and relays it to the hub over a [WireLink] (`/ws/hub`). The CC handling **reuses the
 * shared `:connector-core`** — a [ClaudeCodeSession] built with **wire-relay / no-op seams** — so the
 * CYP-170 lazy-init behaviour (a turn is written ungated; `system/init` arrives during the turn) is
 * inherited, NOT re-derived (the deadlock the directive warns about). Gate #6 reuses the shared [MediationGate].
 *
 *  - **inbound** (hub → agent): a `WireDeliver` is injected as a CC stdin [UserTurn] (serialized per the
 *    session's turn-queue, Gate #5).
 *  - **outbound** (agent → hub): a BOUND session's turn-end result is classified by [MediationGate] (Gate #6)
 *    and sent once as a `WireSend` to the agent's spoke. A stale/unbound result never reaches `onTurnResult`
 *    (the shared session's CYP-170 gate), so it can't be double-delivered.
 */
class BridgeRelay(
    private val agentId: String,
    private val spokeChannel: String,
    private val capabilities: Capabilities,
    private val provider: ProviderInfo,
    process: AgentProcess,
    private val link: WireLink,
    private val scope: CoroutineScope,
    turnQueue: SessionTurnQueue = SessionTurnQueue(),
) {
    private val log = LoggerFactory.getLogger("remote.bridge")

    private val session = ClaudeCodeSession(
        agentId = agentId,
        process = process,
        turnQueue = turnQueue,
        scope = scope,
        // S5/G4: the self-report tap — relays masked rate-limit/tool events as WireEvents over the link,
        // making the bridge's declared rateLimitSignal/toolGranularity=LIMITED honest. (No hub recorder in
        // user infra; this taps the SAME already-masked stream.)
        observer = WireReportingObserver(link, scope),
        onBind = null,                // the bridge has no hub SessionRegistry / durable store
        onUnbind = null,
        onTurnResult = { result ->    // OUTBOUND: a bound session's turn-result → the hub, once (Gate #6)
            val post = MediationGate.classify(result)
            scope.launch {
                runCatching { link.send(WireSend(spokeChannel, post.body, post.kind)) }
                    .onFailure { log.warn("wire send failed for agent={}: {}", agentId, it.message) }
            }
        },
    )

    private var loop: Job? = null

    fun start() {
        session.start()
        loop = scope.launch {
            // Declare honest caps + provider (server clamps REMOTE, E2.4), then subscribe to this agent's spoke.
            link.send(WireHello(capabilities, provider))
            link.send(WireSubscribe(listOf(spokeChannel)))
            link.incoming.collect { frame ->
                when (frame) {
                    is WireDeliver -> runCatching { session.sendTurn(UserTurn(frame.text)) }
                        .onFailure { log.warn("turn injection failed for agent={}: {}", agentId, it.message) }
                    // Ack/Message/Error are server→client acknowledgements; nothing to inject into CC (MVP).
                    else -> log.debug("ignoring inbound frame {} for agent={}", frame::class.simpleName, agentId)
                }
            }
        }
    }

    suspend fun close() {
        loop?.cancel()
        session.closeAndAwait()
        link.close()
    }
}
