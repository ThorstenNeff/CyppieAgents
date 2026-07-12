package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest

/**
 * CYP-486 — the real [OperatorAuthenticator] (RR3 tunnel-auth). After the Noise handshake, over the E2E tunnel
 * and **before any HTTP byte**, it proves operator possession to the hub: build a device-key PoP **bound to the
 * live handshake hash `h`** (via [OperatorPopBuilder]), send **one** [TunnelAuthRequest]`{cpJwt, pop, nonce}`,
 * read **one** [TunnelAuthGrant]. Returns the hub's verdict.
 *
 * **Fail-closed everywhere** — a missing ticket, a local PoP failure (not-enrolled / UV-denied / no
 * authenticator), a closed tunnel (`receive()==null`), or any thrown exception all yield `false`, and
 * [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession] turns `false`/throw into a **terminal**
 * `RemoteFailure.AuthRejected` (no silent retry). A local failure sends **nothing** (no request leaks).
 *
 * **Channel-binding:** the PoP is over `h ‖ hubId ‖ nonce`, and the SAME `nonce` travels in the request — so a
 * grant can't be replayed on another tunnel (a foreign `h` yields a different challenge → the hub rejects).
 * **`cpJwt` is an injected seam** ([CpJwtProvider]): the CP-minted, hub-scoped ticket is the S-K `hubTicket`,
 * which the client does not hold yet — it is **never invented** (absent ⇒ fail-closed).
 */
class ClientOperatorAuth(
    private val popBuilder: OperatorPopBuilder,
    private val cpJwtProvider: CpJwtProvider,
) : OperatorAuthenticator {

    override suspend fun authenticate(tunnel: NoiseTunnel, hubId: String): Boolean {
        // Ticket first (cheap): no ticket ⇒ fail closed WITHOUT prompting the operator to sign. CYP-496: the
        // provider needs the live `h` + hubId to compute the channel-binding `cb` bound to THIS session.
        val jwt = cpJwtProvider.cpJwt(tunnel.handshakeHash, hubId) ?: return false

        // PoP bound to the LIVE handshake hash. Any local failure ⇒ fail closed, no request sent.
        val ready = popBuilder.buildPop(tunnel.handshakeHash, hubId) as? PopBuildOutcome.Ready ?: return false

        // Bind exactly what we built: the same nonce that the PoP challenge used travels in the request.
        val request = TunnelAuthRequest(cpJwt = jwt, pop = ready.pop.toWire(), nonce = ready.nonce)
        tunnel.send(CommJson.encodeToString(TunnelAuthRequest.serializer(), request).encodeToByteArray())

        val raw = tunnel.receive() ?: return false // peer closed ⇒ not granted
        val grant = CommJson.decodeFromString(TunnelAuthGrant.serializer(), raw.decodeToString())
        return grant.granted
    }
}

/**
 * Supplies the CP-minted, hub-scoped identity ticket for [TunnelAuthRequest.cpJwt] (the S-K `hubTicket`).
 * A seam because the client does not hold that ticket yet (S-K); the composition root wires the real provider
 * when it lands. `null` ⇒ fail-closed. Never fabricate a ticket.
 */
fun interface CpJwtProvider {
    /**
     * The CP-minted hub ticket for THIS session, or `null` (fail-closed). CYP-496: the real provider requests it
     * from the CP after the Noise handshake, binding it to the live [handshakeHash] `h` + [hubId] via the
     * channel-binding `cb`. `null` on any failure (no session / typed CP rejection / unreachable) — never invented.
     */
    suspend fun cpJwt(handshakeHash: ByteArray, hubId: String): String?
}

/** Map the app-internal [DevicePoP] onto the `:core` [OperatorPoPWire] wire form — 1:1, no re-shaping. */
internal fun DevicePoP.toWire(): OperatorPoPWire = when (this) {
    is DevicePoP.Raw -> OperatorPoPWire.Raw(signature)
    is DevicePoP.Fido2 -> OperatorPoPWire.Fido2(credentialId, authenticatorData, signature)
}
