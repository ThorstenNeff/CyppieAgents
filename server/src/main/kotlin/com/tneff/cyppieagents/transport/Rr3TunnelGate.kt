package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.IdentityToken
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.auth.operator.AssertionResult
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.auth.operator.OperatorDevicePoP
import com.tneff.cyppieagents.auth.operator.OperatorDeviceStore
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest

/**
 * CYP-459 (S3) — the **RR3 tunnel-auth gate**. Immediately after the Noise handshake, before any HTTP byte, it reads
 * the client's one [TunnelAuthRequest] off the tunnel, verifies it against the tunnel's **LIVE** handshake hash `h`,
 * replies one [TunnelAuthGrant], and returns whether the tunnel is authorized. **Only if authorized does the caller
 * bridge** ([NoiseRelayConnector]'s composite `tunnelHandler`); else it closes the tunnel. Reject is **terminal**.
 *
 * Two independent verifications, **AND-conjoined**, both bound to the SAME live `h`:
 *  - **CpJwt** ([IdentityToken] / S-E CYP-447): CP-signed operator identity — `iss`, `aud==hubId`, `exp/nbf`,
 *    `sub==pinnedOperatorId` (F6), and the channel-binding `cb == base64url(SHA-256(h ‖ hubId))` (anti-cross-session
 *    replay, CB-x1). A token minted for a different session's `h` fails here.
 *  - **Operator PoP** ([OperatorAssertionVerifier] / CYP-469): the enrolled device signs the live-`h` challenge
 *    (`operatorAuthChallenge(h, hubId, nonce)`); the CP forges the identity token but never this (anti-seizure).
 *
 * **T2 preserved:** this gate never injects trust into the route — the bridge stays a dumb byte-pump and the Ktor
 * route re-verifies its own credential. RR3 is an orthogonal second gate at the tunnel boundary, never laundered in.
 * **No oracle:** every reject returns the SAME stable, non-secret code regardless of which conjunct failed (the
 * differential zero-privilege claim). Fail-closed throughout.
 */
class Rr3TunnelGate(
    private val cpJwtVerifier: IdentityToken,
    private val operatorVerifier: OperatorAssertionVerifier,
    private val deviceStore: OperatorDeviceStore,
    private val config: Rr3Config,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /** Read → verify against live `h` → reply → return true iff authorized (the caller bridges). Fail-closed. */
    suspend fun authorize(tunnel: ServerNoiseTunnel): Boolean {
        val h = tunnel.handshakeHash
        // CB-d1 (CYP-490): a **load-bearing fail-early short-circuit** — a non-32-byte `h` is rejected HERE, before
        // any verify runs (the CpJwt verify is never reached: proven by the short-circuit spy tooth). It is NOT the
        // sole `h`-guard — the downstream `cb` channel-binding (`SHA-256(h ‖ hubId)`) is redundant defence-in-depth —
        // but short-circuiting keeps a malformed `h` out of the `cb` computation entirely (`h` is always 32 B from
        // BLAKE2s, so a bad size is a bug, not an attack; this fails it closed without processing it).
        if (h.size != 32) return reject(tunnel)

        val req = readRequest(tunnel) ?: return reject(tunnel) // tunnel closed / malformed → uniform reject

        // ★ CB-and-b (CYP-490 fix): short-circuit on a FAILED CpJwt **before** the operator PoP verify. Otherwise the
        // PoP verify (which consumes the single-use nonce on a valid signature) runs even when the CpJwt is invalid —
        // a bad-CpJwt + valid-PoP would pre-burn the operator's nonce (a grief/DoS, CYP-477-class: the operator's
        // legitimate retry with that nonce is then rejected as a replay). The nonce must be consumed ONLY once the
        // CpJwt is valid AND the PoP is genuinely processed. a∧b stays a∧b — this only orders the evaluation.
        val principal = cpJwtVerifier.verify(
            req.cpJwt,
            VerifierContext(
                hubId = config.hubId,
                pinnedOperatorId = config.pinnedOperatorId,
                handshakeHash = h,
                expectedIssuer = config.expectedIssuer,
                cpPublicKey = config.cpPublicKey,
                nowMs = now(),
            ),
        )
        if (principal == null) return reject(tunnel) // a failed → reject; the PoP verify (and its nonce) is NOT reached

        val device = deviceStore.enrolled()
        val popVerified = device != null && operatorVerifier.verify(
            req.pop.toOperatorDevicePoP(), device, h, config.hubId, req.nonce, config.expectedRpId,
        ) is AssertionResult.Verified

        return if (popVerified) grant(tunnel) else reject(tunnel)
    }

    private suspend fun readRequest(tunnel: ServerNoiseTunnel): TunnelAuthRequest? = runCatching {
        val raw = tunnel.receive() ?: return null
        CommJson.decodeFromString<TunnelAuthRequest>(raw.decodeToString())
    }.getOrNull()

    private suspend fun grant(tunnel: ServerNoiseTunnel): Boolean {
        reply(tunnel, TunnelAuthGrant(granted = true))
        return true
    }

    private suspend fun reject(tunnel: ServerNoiseTunnel): Boolean {
        reply(tunnel, TunnelAuthGrant(granted = false, reason = REJECT_CODE))
        return false
    }

    private suspend fun reply(tunnel: ServerNoiseTunnel, grant: TunnelAuthGrant) {
        runCatching { tunnel.send(CommJson.encodeToString(grant).encodeToByteArray()) }
    }

    private companion object {
        /** The one uniform, non-secret reject code — same for bad-h / malformed / bad-CpJwt / bad-PoP (no oracle). */
        const val REJECT_CODE = "auth_failed"
    }
}

/** The RR3 gate's config inputs (from S-C HubIdentity + the CP pin + config; supplied by the CYP-459 boot-wiring). */
data class Rr3Config(
    val hubId: String,
    val pinnedOperatorId: String,
    val expectedIssuer: String,
    val cpPublicKey: (kid: String?) -> ByteArray?,
    val expectedRpId: String,
)

/** Adapter at the wire edge: the `:core` [OperatorPoPWire] → the server verify-side [OperatorDevicePoP] (the internal
 *  types stay put; only the wire form is consolidated — additive-safe). */
internal fun OperatorPoPWire.toOperatorDevicePoP(): OperatorDevicePoP = when (this) {
    is OperatorPoPWire.Raw -> OperatorDevicePoP.Raw(signature)
    is OperatorPoPWire.Fido2 -> OperatorDevicePoP.Fido2(credentialId, authenticatorData, signature)
}
