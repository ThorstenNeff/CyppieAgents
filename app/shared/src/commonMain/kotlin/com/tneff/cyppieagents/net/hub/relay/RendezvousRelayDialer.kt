package com.tneff.cyppieagents.net.hub.relay

import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.RelayDialException
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure

/**
 * CYP-494 (Runway #1) — the real client [RelayDialer]: resolve the hub's rendezvous via the CP ([resolver]),
 * then dial the relay WS under the opaque id ([connector]). A typed resolve failure throws a [RelayDialException]
 * carrying the mapped [RemoteFailure] so the session surfaces the RIGHT cause (NOT_REGISTERED → HubOffline,
 * RELAY_UNAVAILABLE → RelayUnreachable), not a generic RelayUnreachable. Fail-closed: a resolve failure NEVER
 * dials the relay.
 *
 * Still INERT in the assembly until activation: the jvm assembly keeps its fail-closed `gatedRelayDialer` until
 * the [RendezvousResolver] HTTP impl (CYP-507 `:core` DTO) lands + `CYPPIE_REMOTE_RELAY_URL`-style GO — then it
 * swaps in `RendezvousRelayDialer(HttpRendezvousResolver(cp), KtorWsRelayConnector(wsClient))`.
 */
class RendezvousRelayDialer(
    private val resolver: RendezvousResolver,
    private val connector: RelayWsConnector,
) : RelayDialer {
    override suspend fun dial(hubId: String): RelayChannel =
        when (val resolution = resolver.resolve(hubId)) {
            is RendezvousResolution.Bound -> connector.open(resolution.relayUrl, resolution.rendezvousId)
            is RendezvousResolution.Failed -> throw RelayDialException(resolution.cause.toRemoteFailure())
        }
}

/** The typed resolve failure → the operator-facing [RemoteFailure] the connect view renders (§ CYP-471). */
internal fun RendezvousUnavailable.toRemoteFailure(): RemoteFailure = when (this) {
    RendezvousUnavailable.NOT_REGISTERED -> RemoteFailure.HubOffline
    RendezvousUnavailable.RELAY_UNAVAILABLE -> RemoteFailure.RelayUnreachable
}
