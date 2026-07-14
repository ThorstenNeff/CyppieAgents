package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.AuthPrincipal
import com.tneff.cyppieagents.auth.IdentityToken
import com.tneff.cyppieagents.auth.VerifierContext
import com.tneff.cyppieagents.auth.operator.AssertionResult
import com.tneff.cyppieagents.auth.operator.BackupCodeStore
import com.tneff.cyppieagents.auth.operator.DeviceKeyAlg
import com.tneff.cyppieagents.auth.operator.EnrollResult
import com.tneff.cyppieagents.auth.operator.EnrolledOperatorDevice
import com.tneff.cyppieagents.auth.operator.FinalizedEnrollment
import com.tneff.cyppieagents.auth.operator.FinalizedEnrollmentStore
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.auth.operator.OperatorDeviceEnrollment
import com.tneff.cyppieagents.auth.operator.OperatorDevicePoP
import com.tneff.cyppieagents.auth.operator.OperatorDeviceStore
import com.tneff.cyppieagents.operator.BACKUP_CODE_COUNT
import com.tneff.cyppieagents.operator.EnrollResponse
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.SavedAck
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import com.tneff.cyppieagents.operator.ed25519PublicKeyToRaw
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

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
    /** CYP-525 GE5/GE7 — when present, the gate runs the ratified **provisional → SavedAck → combined-atomic-finalize**
     *  flow (the anchor + code-hashes commit together, H1/H2). The anchor is then read from [FinalizedEnrollmentStore].
     *  `null` = the pre-GE5 immediate-enroll path over [deviceStore] (existing tests / not-yet-wired). */
    private val finalizedStore: FinalizedEnrollmentStore? = null,
    private val backupCodes: BackupCodeStore = BackupCodeStore(),
    /** CYP-525 — bounded await for the client's [SavedAck] (lock-liveness: a hung provisional times out → discard → release). */
    private val savedAckTimeoutMs: Long = 30_000L,
) {
    // CYP-525 — First-Device-Enroll over the store (rejects a re-enroll; validates the key). Constructed from the same
    // store the verify reads, so no constructor change / no wiring change: the gate self-serves TOFU first-enroll.
    private val enrollment = OperatorDeviceEnrollment(deviceStore)

    /** CYP-525 H2 — serialize first-enroll: ONE provisional in-flight (per-hub, this per-gate instance = per-hub). */
    private val firstEnrollLock = Mutex()

    private val log = LoggerFactory.getLogger("cyp525.rr3gate")

    /**
     * CYP-525 H1b (self-re-read finding) — a corrupt/tampered FINALIZED anchor. After atomic-rename a torn/partial
     * record cannot arise, so an unreadable record = **deliberate tampering** → fail-CLOSED: reject cleanly (never
     * "empty → re-enroll" = the seizure vector) and NEVER let the throw propagate uncaught (which would loop the
     * CYP-526 reconnect + surface no signal). Log the distinct §4 diagnostic so the human triggers OOB recovery.
     */
    private suspend fun rejectTampered(tunnel: ServerNoiseTunnel, e: Exception): Boolean {
        log.warn("CYP-525 operator anchor unreadable (tampered/corrupt) — refusing ALL connects, OOB recovery required: {}", e.message)
        return reject(tunnel)
    }

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

        // CYP-525 GE5/GE7 — the FINALIZED anchor comes from the combined [finalizedStore] when wired (else the pre-GE5
        // [deviceStore]). A TAMPERED finalized record is fail-CLOSED with a distinct diagnostic (rejectTampered) — never
        // a silent "empty → re-enroll" over a corrupt anchor (the tamper→re-enroll seizure vector), and never an uncaught
        // throw (H1b self-re-read finding). read() is non-suspend, so this catch cannot swallow a cancellation.
        val finalizedDevice = try { finalizedStore?.read()?.device } catch (e: Exception) { return rejectTampered(tunnel, e) }
        val anchor = finalizedDevice ?: deviceStore.enrolled()

        if (anchor == null) {
            // EMPTY: TOFU first-enroll under a CpJwt-authenticated operator (CT-2b; only reachable past a valid CpJwt ⇒
            // no land-grab). GE5/GE7 provisional→SavedAck→combined-atomic-finalize when wired; else pre-GE5 immediate.
            return if (finalizedStore != null) provisionalFinalizeFlow(tunnel, req, h, principal, finalizedStore)
            else firstEnrollThenGrant(tunnel, req, h, principal)
        }

        // Steady-state (already Finalized): the PoP may match the enrolled anchor; the nonce is consumed once, only on
        // a match (the verifyAny grief-guard). Both guards held together (CYP-490 ∧ CYP-485).
        val devices = finalizedDevice?.let { listOf(it) } ?: deviceStore.devices()
        val popVerified = operatorVerifier.verifyAny(
            req.pop.toOperatorDevicePoP(), devices, h, config.hubId, req.nonce, config.expectedRpId,
        ) is AssertionResult.Verified

        return if (popVerified) grant(tunnel, firstEnroll = false) else reject(tunnel)
    }

    /**
     * CYP-525 GE5/GE7 — the ratified provisional → finalize first-enroll. Serialized (H2, [firstEnrollLock]); the codes
     * of THIS session are minted local + persisted with the anchor as ONE combined [FinalizedEnrollment] record written
     * crash-atomically (H1/H1b). No `SavedAck` (drop / nack / bounded timeout) → discard → nothing committed → re-mint
     * next connect (no-lockout). The lock is released in `finally` on every exit (liveness — no self-DoS).
     */
    private suspend fun provisionalFinalizeFlow(
        tunnel: ServerNoiseTunnel, req: TunnelAuthRequest, h: ByteArray, principal: AuthPrincipal, store: FinalizedEnrollmentStore,
    ): Boolean {
        val rawPubkey = req.devicePublicKey?.let { runCatching { ed25519PublicKeyToRaw(it) }.getOrNull() }
            ?: return reject(tunnel) // no (valid) device key on the wire ⇒ nothing to anchor
        val ownerId = (principal as? AuthPrincipal.Human)?.identityId ?: config.pinnedOperatorId
        val candidate = EnrolledOperatorDevice(deviceId = ownerId, alg = DeviceKeyAlg.ED25519, publicKey = rawPubkey, credentialId = null)
        // Proof-of-possession against the PRESENTED key BEFORE anything is minted/committed (nonce consumed only on match).
        val proven = operatorVerifier.verifyAny(
            req.pop.toOperatorDevicePoP(), listOf(candidate), h, config.hubId, req.nonce, config.expectedRpId,
        ) is AssertionResult.Verified
        if (!proven) return reject(tunnel)
        // H2 — serialize: only ONE provisional in-flight (per-hub). A 2nd concurrent first-connect is rejected.
        if (!firstEnrollLock.tryLock()) return reject(tunnel)
        try {
            // recheck under the lock; a tampered record here is also fail-closed diagnostic (the `finally` still unlocks).
            val existing = try { store.read() } catch (e: Exception) { return rejectTampered(tunnel, e) }
            // CYP-554 (CYP-550 finding ②): a concurrent first-enroll already committed the anchor. Do NOT grant this
            // provisional — its PoP was verified (above) against its OWN presented key, NOT the enrolled anchor, so
            // granting here would admit a tunnel that never proved possession of the enrolled device (defeating the PoP
            // anti-seizure under a forged-CpJwt CP: an adversary with a forged CpJwt + its own key would win CONNECTED
            // in this race). REJECT instead: the operator reconnects to the steady-state path, which verifies the PoP
            // against the enrolled anchor with a FRESH nonce (the legit same-device operator's key matches → grants; a
            // forged-CpJwt adversary's own key → bad_signature → stays rejected). Re-verifying HERE cannot reuse this
            // request's nonce — it was already single-use-consumed by the verifyAny above — so reconnect is the clean path.
            if (existing != null) return reject(tunnel)
            val minted = backupCodes.mint(BACKUP_CODE_COUNT)                     // THIS session's codes, local (not persisted)
            reply(tunnel, TunnelAuthGrant(granted = true, firstEnroll = true))   // provisional grant → the client reveals
            runCatching { tunnel.send(CommJson.encodeToString(EnrollResponse(minted.plaintexts)).encodeToByteArray()) }
                .getOrElse { return false }                                      // send failed → discard (nothing committed)
            // Bounded await for the user-saved ack (liveness). No ack / timeout / nack → discard → close (re-mint next).
            val ack = withTimeoutOrNull(savedAckTimeoutMs) { readSavedAck(tunnel) }
            if (ack?.ok != true) return false
            // FINALIZE: the anchor + THIS session's code-hashes commit TOGETHER as ONE crash-atomic record (H1/H2).
            store.commit(FinalizedEnrollment(candidate, minted.entries))
            reply(tunnel, TunnelAuthGrant(granted = true, firstEnroll = false))  // ★ post-commit CONNECTED grant
            return true
        } finally {
            firstEnrollLock.unlock() // liveness: released on finalize / discard / timeout — a hung provisional never holds it
        }
    }

    private suspend fun readSavedAck(tunnel: ServerNoiseTunnel): SavedAck? = runCatching {
        val raw = tunnel.receive() ?: return null
        CommJson.decodeFromString<SavedAck>(raw.decodeToString())
    }.getOrNull()

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

    private suspend fun grant(tunnel: ServerNoiseTunnel, firstEnroll: Boolean = false): Boolean {
        reply(tunnel, TunnelAuthGrant(granted = true, firstEnroll = firstEnroll))
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
