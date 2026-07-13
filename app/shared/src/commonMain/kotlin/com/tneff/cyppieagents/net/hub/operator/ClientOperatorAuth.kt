package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest

/**
 * CYP-486 — the real [OperatorAuthenticator] (RR3 tunnel-auth). After the Noise handshake, over the E2E tunnel
 * and **before any HTTP byte**, it proves operator possession to the hub: build a device-key PoP **bound to the
 * live handshake hash `h`** (via [OperatorPopBuilder]), send **one** [TunnelAuthRequest]`{cpJwt, pop, nonce}`,
 * read **one** [TunnelAuthGrant]. Returns the hub's verdict.
 *
 * **Fail-closed, three distinct truths (CYP-525)** — returns [OperatorAuthOutcome]: a **not-enrolled** device
 * (checked FIRST, before any ticket fetch or UV prompt) ⇒ [OperatorAuthOutcome.DeviceNotEnrolled] (→ the enroll
 * step, NEVER a reject — the bug this fixes); a missing ticket, any other local PoP failure (UV-denied / no
 * authenticator), a closed tunnel (`receive()==null`), or a thrown exception ⇒ [OperatorAuthOutcome.Rejected];
 * only a real hub grant ⇒ [OperatorAuthOutcome.Granted]. [com.tneff.cyppieagents.net.hub.remote.RemoteHubSession]
 * maps DeviceNotEnrolled → `RemoteFailure.DeviceNotEnrolled` and Rejected → `RemoteFailure.AuthRejected` (both
 * terminal, no silent retry). A local failure sends **nothing** (no request leaks).
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

    override suspend fun authenticate(tunnel: NoiseTunnel, hubId: String): OperatorAuthOutcome {
        // CYP-525: isEnrolled FIRST — "this device isn't set up" is an ENROLL step, never a reject. We don't even
        // fetch the ticket or prompt the operator to sign; we route to enroll (DeviceNotEnrolled), distinct truth.
        if (!popBuilder.isEnrolled()) return OperatorAuthOutcome.DeviceNotEnrolled

        // Ticket next (cheap): no ticket ⇒ fail closed WITHOUT prompting the operator to sign. CYP-496: the
        // provider needs the live `h` + hubId to compute the channel-binding `cb` bound to THIS session.
        val jwt = cpJwtProvider.cpJwt(tunnel.handshakeHash, hubId) ?: return OperatorAuthOutcome.Rejected

        // PoP bound to the LIVE handshake hash. A local failure ⇒ fail closed, no request sent — but "not enrolled"
        // (a race after the pre-check) still routes to enroll, never a reject; any other local failure is Rejected.
        return when (val built = popBuilder.buildPop(tunnel.handshakeHash, hubId)) {
            is PopBuildOutcome.Ready -> {
                // Bind exactly what we built: the same nonce that the PoP challenge used travels in the request.
                // CYP-525: also carry the raw-32B device public key so the hub can TOFU first-enroll it (the hub
                // ignores it once a device is pinned; it's the operator's own already-known key, never a secret).
                val request = TunnelAuthRequest(
                    cpJwt = jwt,
                    pop = built.pop.toWire(),
                    nonce = built.nonce,
                    devicePublicKey = popBuilder.devicePublicKeyRaw(),
                )
                tunnel.send(CommJson.encodeToString(TunnelAuthRequest.serializer(), request).encodeToByteArray())
                val raw = tunnel.receive() ?: return OperatorAuthOutcome.Rejected // peer closed ⇒ not granted
                val grant = CommJson.decodeFromString(TunnelAuthGrant.serializer(), raw.decodeToString())
                if (grant.granted) OperatorAuthOutcome.Granted else OperatorAuthOutcome.Rejected
            }
            PopBuildOutcome.NotEnrolled -> OperatorAuthOutcome.DeviceNotEnrolled
            // CYP-525 F3: a local UV failure (wrong PIN / cancelled) is RETRYABLE — never a hub reject (nothing sent).
            is PopBuildOutcome.UvFailed -> OperatorAuthOutcome.UvFailed
            PopBuildOutcome.AuthenticatorUnavailable -> OperatorAuthOutcome.Rejected // no authenticator here ⇒ fail-closed
        }
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
