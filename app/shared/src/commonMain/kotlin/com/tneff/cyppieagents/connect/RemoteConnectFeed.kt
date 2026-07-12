package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CYP-471 (Epic CYP-427 Phase-2) — the **remote** connect feed, mirroring [LocalConnectFeed] for the Noise-E2E
 * path. Emits the honest [RemoteSessionState] progression (`RemoteConnState` relayDialing→e2eHandshake→trustCheck→
 * authenticating→connected, plus reconnect / terminal). The live impl (post-RR5) wraps a
 * [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession] (start + emit `session.state`; cancellation → `close()`,
 * which is the Q5 exactly-one-hub teardown). **Stub-first** ([StubRemoteConnectFeed]) until RR5 — honestly labelled.
 */
fun interface RemoteConnectFeed {
    fun connect(hub: HubDescriptor): Flow<RemoteSessionState>
}

/**
 * Stub feed (stub-driven until RR5): scripts the connect progression for [hub], ending in [terminal] (a real
 * hub grant when null → CONNECTED, or a failure). Mirrors [StubLocalConnectFeed]. No real Noise — the UI renders
 * against these honest states exactly as it will against the live session.
 */
class StubRemoteConnectFeed(
    private val terminal: RemoteFailure? = null,
) : RemoteConnectFeed {
    override fun connect(hub: HubDescriptor): Flow<RemoteSessionState> = flow {
        fun st(conn: RemoteConnState, failure: RemoteFailure? = null) =
            RemoteSessionState(hubId = hub.hubId, conn = conn, failure = failure)
        emit(st(RemoteConnState.RELAY_DIALING))
        emit(st(RemoteConnState.E2E_HANDSHAKE))
        emit(st(RemoteConnState.TRUST_CHECK))
        emit(st(RemoteConnState.AUTHENTICATING))
        if (terminal == null) {
            emit(st(RemoteConnState.CONNECTED))
        } else {
            emit(st(RemoteConnState.LOST, failure = terminal))
        }
    }

    companion object {
        /** A feed that ends in a specific failure (e.g. a terminal TrustChanged/AuthRejected, or a retryable one). */
        fun failing(failure: RemoteFailure) = StubRemoteConnectFeed(terminal = failure)
    }
}
