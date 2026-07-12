package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.remote.RemoteHubSession
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * CYP-486 live-wiring — the **live** [RemoteConnectFeed] (the post-RR5 replacement for [StubRemoteConnectFeed]).
 * Per the [RemoteConnectFeed] contract, it wraps a [RemoteHubSession]: on collect it builds the session (via
 * [sessionFactory]), `start()`s it, emits its `state`, and on cancellation — the CYP-429 Q5 exactly-one-hub
 * switch / teardown — calls `close()` (the idempotent Q5 teardown). One session per `connect`, scoped to the
 * collecting flow so structured concurrency tears the loop down.
 *
 * **The feed is complete; the ASSEMBLY it consumes is still partly gated.** A real [RemoteHubSession] needs a
 * real [com.tneff.cyppieagents.net.hub.remote.RelayDialer] (CP rendezvous + relay WS — **no client impl yet**,
 * RR4/RR5), a [com.tneff.cyppieagents.net.hub.operator.CpJwtProvider] (the S-K hub ticket — deferred), and the
 * registry `dhPubKey` (not on the client `HubDescriptor` yet). Until those land the [sessionFactory] injects
 * fail-closed seams, so a flipped remote-mode flag connects to **nothing** (fails closed) — never a fake
 * success. This keeps the wiring ready without activating an incomplete remote path.
 */
class RemoteHubSessionConnectFeed(
    private val sessionFactory: RemoteHubSessionFactory,
) : RemoteConnectFeed {

    override fun connect(hub: HubDescriptor): Flow<RemoteSessionState> = flow {
        coroutineScope {
            val session = sessionFactory.create(hub, this)
            session.start()
            try {
                // A StateFlow never completes → emit until the collector is cancelled (Q5 switch / teardown).
                session.state.collect { emit(it) }
            } finally {
                // Idempotent Q5 teardown; run even as the scope unwinds so the tunnel is always closed.
                withContext(NonCancellable) { session.close() }
            }
        }
    }
}

/**
 * Builds a [RemoteHubSession] for [hub] within [scope] (the connecting flow's scope, so a cancel tears the loop
 * down). The real impl assembles the Noise stack (`NoiseJavaClientTransport` + `TofuHubTrust` +
 * `ClientOperatorAuth`) — but the relay dialer / cpJwt / dhPubKey seams are still gated (see
 * [RemoteHubSessionConnectFeed]); a jvm assembly injects the real parts and fail-closed stubs for the rest.
 */
fun interface RemoteHubSessionFactory {
    fun create(hub: HubDescriptor, scope: CoroutineScope): RemoteHubSession
}
