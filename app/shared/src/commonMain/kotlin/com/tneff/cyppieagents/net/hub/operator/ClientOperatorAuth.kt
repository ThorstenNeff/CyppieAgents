package com.tneff.cyppieagents.net.hub.operator

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.operator.EnrollResponse
import com.tneff.cyppieagents.operator.OperatorPoPWire
import com.tneff.cyppieagents.operator.SavedAck
import com.tneff.cyppieagents.operator.TunnelAuthGrant
import com.tneff.cyppieagents.operator.TunnelAuthRequest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** CYP-595 — the bound on each RR3 network receive (the grant / enroll codes / post-SavedAck final grant). Generous
 *  (the hub's fsync/rename finalize is quick), but bounded so a hub stall surfaces a retryable "session expired"
 *  instead of an eternal "Confirming operator" hang. The user-saved confirm is a SEPARATE, unbounded user wait. */
const val ENROLL_FINALIZE_TIMEOUT_MS: Long = 30_000L

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
    /** CYP-525 §2 — the first-enroll user-saved confirmer. On `grant.firstEnroll`, after the (H3-validated)
     *  EnrollResponse codes arrive, this surfaces the reveal + suspends until the operator confirms "saved" (→ send
     *  SavedAck) or aborts (→ fail-closed, no SavedAck). Default = fail-closed no-op (INERT: first-enroll aborts). */
    private val enrollConfirmer: EnrollConfirmer = EnrollConfirmer { false },
    /** CYP-595 — the per-receive network bound (see [ENROLL_FINALIZE_TIMEOUT_MS]). Injectable so a test drives it fast. */
    private val enrollFinalizeTimeoutMs: Long = ENROLL_FINALIZE_TIMEOUT_MS,
) : OperatorAuthenticator {

    override suspend fun authenticate(tunnel: NoiseTunnel, hubId: String): OperatorAuthOutcome {
        // CYP-525: isEnrolled FIRST — "this device isn't set up" is an ENROLL step, never a reject. We don't even
        // fetch the ticket or prompt the operator to sign; we route to enroll (DeviceNotEnrolled), distinct truth.
        if (!popBuilder.isEnrolled()) return OperatorAuthOutcome.DeviceNotEnrolled

        // Ticket next: no ticket ⇒ fail closed WITHOUT prompting the operator to sign. CYP-496: the provider needs
        // the live `h` + hubId to compute the channel-binding `cb` bound to THIS session.
        // CYP-595 (R2 follow-up): the real provider's cpJwt fetch is a suspend CP HTTP roundtrip, and the shared
        // client installs NO HttpTimeout — so a jammed CP (accept-then-silent) would hang HERE, one step ABOVE the
        // bounded RR3 receives, relocating the eternal hang instead of closing it. Bound it with the SAME window ⇒ a
        // stalled CP roundtrip surfaces the retryable EnrollTimedOut, engine-agnostic, symmetric with the receives.
        val jwt = try {
            bounded { cpJwtProvider.cpJwt(tunnel.handshakeHash, hubId) }
        } catch (e: EnrollFinalizeTimeout) {
            return OperatorAuthOutcome.EnrollTimedOut // a jammed CP roundtrip ⇒ retryable reconnect, never a pre-protocol hang
        } ?: return OperatorAuthOutcome.Rejected

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
                runEnrollProtocol(tunnel)
            }
            PopBuildOutcome.NotEnrolled -> OperatorAuthOutcome.DeviceNotEnrolled
            // CYP-525 F3: a local UV failure (wrong PIN / cancelled) is RETRYABLE — never a hub reject (nothing sent).
            is PopBuildOutcome.UvFailed -> OperatorAuthOutcome.UvFailed
            PopBuildOutcome.AuthenticatorUnavailable -> OperatorAuthOutcome.Rejected // no authenticator here ⇒ fail-closed
        }
    }

    /**
     * CYP-525 §2 — the finalization protocol over the tunnel (the [TunnelAuthRequest] is already sent):
     *  read Grant → **steady-state** (`granted ∧ !firstEnroll`) IS CONNECTED (Granted); **first-enroll** → read
     *  [EnrollResponse], H3-validate (fail-closed), confirm user-saved (abort ⇒ fail-closed, NO `SavedAck` ⇒ the hub
     *  discards the provisional), send [SavedAck], then read the **final** Grant `{granted ∧ !firstEnroll}` = CONNECTED
     *  (an explicit frame emitted only after the hub's fsync/rename finalize). The client **never infers** CONNECTED
     *  from the byte-bridge. A closed tunnel / non-grant at any step ⇒ fail-closed Rejected.
     */
    private suspend fun runEnrollProtocol(tunnel: NoiseTunnel): OperatorAuthOutcome = try {
        // CYP-595: EVERY network receive is bounded ([bounded]) so a hub stall surfaces a retryable EnrollTimedOut,
        // never an eternal "Confirming operator" hang. The user-saved confirm below is a SEPARATE, UNBOUNDED user wait.
        val grant = bounded { readGrant(tunnel) } ?: return OperatorAuthOutcome.Rejected
        if (!grant.granted) return OperatorAuthOutcome.Rejected
        if (!grant.firstEnroll) return OperatorAuthOutcome.Granted // steady-state: this grant IS CONNECTED

        val enrollRaw = bounded { tunnel.receive() } ?: return OperatorAuthOutcome.Rejected
        val enroll = CommJson.decodeFromString(EnrollResponse.serializer(), enrollRaw.decodeToString())
        // H3: never show / ack an invalid (empty/truncated/over-count/blank) set — fail-closed (no SavedAck ⇒ discard).
        // CYP-525 Finding ①: an invalid set means the codes did NOT arrive intact — a DELIVERY problem (retryable),
        // NOT a hub reject. Surface EnrollCodesUnavailable (retryable-reconnect), never the terminal Rejected/AuthRejected
        // mis-attribution. Fail-closed stays: no confirm, no SavedAck, no CONNECTED.
        if (!isValidCodeSet(enroll.backupCodes)) return OperatorAuthOutcome.EnrollCodesUnavailable
        // Surface the ONE reveal + suspend until the operator confirms "saved" — an UNBOUNDED USER wait (never timed
        // out; saving codes can take minutes). Abort ⇒ fail-closed, no SavedAck.
        if (!enrollConfirmer.confirmSavedCodes(enroll.backupCodes)) return OperatorAuthOutcome.Rejected
        tunnel.send(CommJson.encodeToString(SavedAck.serializer(), SavedAck()).encodeToByteArray())
        // CYP-595 (the live-hang spot): the post-SavedAck final grant. Bounded — a hub finalize-stall ⇒ EnrollTimedOut
        // (retryable "window expired — reconnect"), never the eternal spinner. CONNECTED = granted ∧ !firstEnroll.
        val finalGrant = bounded { readGrant(tunnel) } ?: return OperatorAuthOutcome.Rejected
        if (finalGrant.granted && !finalGrant.firstEnroll) OperatorAuthOutcome.Granted else OperatorAuthOutcome.Rejected
    } catch (e: EnrollFinalizeTimeout) {
        OperatorAuthOutcome.EnrollTimedOut // a bounded RR3 receive exceeded its window ⇒ retryable reconnect (not a hang)
    }

    /** CYP-595 — run [block] under the per-network-op bound; a timeout throws [EnrollFinalizeTimeout] (caught at the
     *  cpJwt fetch → [OperatorAuthOutcome.EnrollTimedOut], and at [runEnrollProtocol] → the same). Guards BOTH the
     *  pre-protocol CP-ticket roundtrip (R2 follow-up) AND every RR3 receive, so neither a jammed CP nor a hub stall
     *  hangs the enroll auth. A REAL structured cancellation still propagates (we convert only the
     *  [TimeoutCancellationException] `withTimeout` raises). */
    private suspend fun <T> bounded(block: suspend () -> T): T =
        try {
            withTimeout(enrollFinalizeTimeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw EnrollFinalizeTimeout()
        }

    private suspend fun readGrant(tunnel: NoiseTunnel): TunnelAuthGrant? =
        tunnel.receive()?.let { CommJson.decodeFromString(TunnelAuthGrant.serializer(), it.decodeToString()) }
}

/** CYP-595 — internal marker: a bounded RR3 network receive exceeded its window (→ retryable [OperatorAuthOutcome.EnrollTimedOut]). */
private class EnrollFinalizeTimeout : Exception()

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
