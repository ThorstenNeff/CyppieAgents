package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.IdentityToken
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.auth.operator.AssertionResult
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrollResult
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.auth.operator.OperatorDeviceEnrollment
import com.tneff.cyppieagents.auth.operator.OperatorDevicePoP
import com.tneff.cyppieagents.auth.operator.OperatorDeviceStore
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.ed25519PublicKeyToRaw

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
    // CYP-525 — First-Device-Enroll over the store (rejects a re-enroll; validates the key). Constructed from the same
    // store the verify reads, so no constructor change / no wiring change: the gate self-serves TOFU first-enroll.
    private val enrollment = OperatorDeviceEnrollment(deviceStore)

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
        // ★ CB-and-b (CYP-490): a failed CpJwt → reject BEFORE the PoP verify, so a bad-CpJwt attempt never consumes
        // the operator's single-use nonce (grief pre-burn, CYP-477-class). The nonce is consumed only once the CpJwt
        // is valid AND the PoP is genuinely processed.
        if (principal == null) return reject(tunnel)

        // CYP-525 (2-iii) — TOFU first-enroll under a CpJwt-authenticated operator. On an EMPTY store the first PoP
        // that PROVES possession of its presented device key anchors that key; the owner is the AUTHENTICATED operator
        // ([principal], CT-2b — never a payload claim), so we only reach here past a valid CpJwt ⇒ no land-grab. A
        // NON-empty store never re-enrolls here (that is the Q6-gated recovery seam, never central-login-alone).
        if (deviceStore.enrolled() == null) return firstEnrollThenGrant(tunnel, req, h, principal)

        // CYP-485 (③ multi-device): the PoP may match ANY enrolled device (verifyAny handles the empty-store case);
        // the nonce is consumed once, only on a match (the verifyAny grief-guard). Both guards held together (CYP-490 ∧ CYP-485).
        val popVerified = operatorVerifier.verifyAny(
            req.pop.toOperatorDevicePoP(), deviceStore.devices(), h, config.hubId, req.nonce, config.expectedRpId,
        ) is AssertionResult.Verified

        return if (popVerified) grant(tunnel) else reject(tunnel)
    }

    /**
     * CYP-525 first-enroll (the ratified (a) Raw/Ed25519 factor): the client sends its raw-32B device public key
     * ([TunnelAuthRequest.devicePublicKey]) — a raw Ed25519 key is NOT recoverable from the signature, so it must ride
     * the wire, but the OWNER is bound to [principal] (CT-2b), never the key/claim. Proof-of-possession FIRST (verify
     * the PoP against the PRESENTED key), so a key the client can't sign with is never anchored — and the single-use
     * nonce is consumed only on a genuine match (CYP-477/485 grief-guard). Then [OperatorDeviceEnrollment] anchors it
     * (re-checks empty for a concurrent race; validates size). Fail-closed on every branch.
     */
    private suspend fun firstEnrollThenGrant(tunnel: ServerNoiseTunnel, req: TunnelAuthRequest, h: ByteArray, principal: AuthPrincipal): Boolean {
        val rawPubkey = req.devicePublicKey?.let { runCatching { ed25519PublicKeyToRaw(it) }.getOrNull() }
            ?: return reject(tunnel) // no (valid) device key on the wire ⇒ nothing to anchor ⇒ fail-closed
        // CT-2b: the enrolled device is owned by the AUTHENTICATED operator (the CpJwt principal, sub==pinnedOperatorId).
        val ownerId = (principal as? AuthPrincipal.Human)?.identityId ?: config.pinnedOperatorId
        val candidate = EnrolledOperatorDevice(deviceId = ownerId, alg = DeviceKeyAlg.ED25519, publicKey = rawPubkey, credentialId = null)
        val proven = operatorVerifier.verifyAny(
            req.pop.toOperatorDevicePoP(), listOf(candidate), h, config.hubId, req.nonce, config.expectedRpId,
        ) is AssertionResult.Verified
        if (!proven) return reject(tunnel) // never anchor a key the client can't sign with; nonce not consumed on a non-match
        return when (enrollment.enrollFirstDevice(candidate)) {
            is EnrollResult.Enrolled -> grant(tunnel)
            is EnrollResult.Rejected -> reject(tunnel) // e.g. a concurrent first-connect already enrolled
        }
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
