package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.relay.RelayWsConnector
import com.tneff.cyppieagents.net.hub.relay.RendezvousResolution
import com.tneff.cyppieagents.net.hub.relay.RendezvousResolver
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * CYP-537 (M2 Option A, WS2) — the **real** [PoolTunnelDialer], wired against Backend's live WS1 responder
 * (contract C4, `:core RendezvousBinding.rendezvousIds`, CYP-536). Two responsibilities:
 *
 *  - [rendezvousSet] resolves the session's rendezvous ONCE via the CP ([resolver] = `HttpRendezvousResolver`,
 *    `GET /api/cp/rendezvous/{hubId}`) and returns the **epoch-derived N-set MINUS its base id**
 *    (`rendezvousIds.drop(1)`). Element 0 (`== rendezvousId`) is the id the [RemoteHubSession]'s CONTROL tunnel
 *    already paired on (its `RendezvousRelayDialer` dials the base id); the relay pairs **1↔1 per id**, so the pool
 *    dials only id_1..id_{cap-1} → no collision. The ids are CP-derived + opaque — the client NEVER re-derives (the
 *    epoch never leaves the CP). `null` fail-closed on a resolve failure / a `Failed` outcome ⇒ the pool is INERT.
 *  - [dial] opens the relay for a **specific** opaque id via [connector] (id-aware, `X-Cyppie-Rendezvous` +
 *    `role: client`), then runs the same crypto the session ran: resolve trust (Pinned) → Noise_NK against the pin →
 *    operator PoP. It is the **simpler** path than the session's `attemptConnect`: by the time the pool dials (only
 *    AFTER the session reaches CONNECTED) trust is **Pinned** + the device **enrolled**, so no OOB / no enroll —
 *    just a pinned handshake + a Granted PoP, else **fail-closed to `null`**.
 *
 * **Fail-closed, every step:** a resolve/open failure, a `TrustResolution.Changed` (attack — NEVER a silent re-pin,
 * CI-5), a handshake failure, or any non-[OperatorAuthOutcome.Granted] outcome ⇒ `null` (the relay/tunnel is closed
 * first). [CancellationException] always propagates (a switch/leave unwinds, never swallowed as a fail).
 *
 * **Auth cost (C1, WS3):** each [OperatorAuthenticator.authenticate] performs a WebAuthn user-verification; the
 * **UV-per-session caching** (WS3) lands as a UV-caching [authenticator] wrapped around this same seam — this dialer
 * is unchanged when it arrives (it only ever consumes the `authenticate` seam).
 *
 * **Resolve-race note:** the pool resolves the set independently of the session's own resolve. Both hit the CP for
 * the same hubId → the same epoch-set unless the hub re-registers between them (epoch rotates). A mid-connect
 * re-registration would leave the pool's ids on a stale epoch → they simply fail to pair → fail-closed RST (no
 * unsafe fallback). Sharing one resolved binding session↔pool is a later hardening.
 */
class NoisePoolTunnelDialer(
    private val hubId: String,
    private val transport: ClientNoiseTransport,
    private val trust: HubTrust,
    private val authenticator: OperatorAuthenticator,
    private val resolver: RendezvousResolver,
    private val connector: RelayWsConnector,
    private val prologue: ByteArray = ByteArray(0),
) : PoolTunnelDialer {

    private val resolveMutex = Mutex()
    private var resolved: RendezvousResolution.Bound? = null

    /** Resolve the CP rendezvous ONCE (cached); `null` on a resolve failure / typed `Failed` outcome (fail-closed). */
    private suspend fun ensureResolved(): RendezvousResolution.Bound? {
        resolved?.let { return it }
        return resolveMutex.withLock {
            resolved ?: run {
                val resolution = try {
                    resolver.resolve(hubId)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    return@withLock null // CP unreachable ⇒ fail-closed (the pool yields no tunnels)
                }
                (resolution as? RendezvousResolution.Bound)?.also { resolved = it } // Failed ⇒ null
            }
        }
    }

    override suspend fun rendezvousSet(): List<String>? {
        val bound = ensureResolved() ?: return null
        // Drop element 0 (== rendezvousId): the session's CONTROL tunnel already holds it. The pool dials id_1..N.
        // Empty (legacy single-tunnel CP, pre-CYP-536) ⇒ no pool ids ⇒ the pool is INERT (the workspace would run
        // single-flight — that legacy wire is gone once WS1 is deployed; the live N-responder always populates it).
        return bound.rendezvousIds.drop(1)
    }

    override suspend fun dial(rendezvousId: String): NoiseTunnel? {
        val bound = ensureResolved() ?: return null
        // Open the relay for THIS specific CP-derived opaque id (C4): N distinct 1↔1 pairings, relay unchanged.
        val relay = try {
            connector.open(bound.relayUrl, rendezvousId)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            return null // relay unreachable ⇒ fail-closed (the transport RSTs this connection)
        }

        val hubStatic = try {
            when (val resolution = trust.resolve(hubId)) {
                is TrustResolution.Pinned -> resolution.hubStatic
                is TrustResolution.FirstUse -> resolution.hubStatic // session already OOB-confirmed the pin; ride it
                is TrustResolution.Changed -> {
                    runCatching { relay.close() }
                    return null // CI-5 hard block — a changed hub key is NEVER a silently re-pinned pool tunnel
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            runCatching { relay.close() }
            return null
        }

        val tunnel = try {
            transport.connect(hubStatic, relay, prologue) // Noise_NK against the PIN ⇒ hub authenticity (CI-1)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            runCatching { relay.close() }
            return null // handshake failed ⇒ fail-closed
        }

        val outcome = try {
            authenticator.authenticate(tunnel, hubId) // per-tunnel operator PoP over THIS tunnel's `h` (C1)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            runCatching { tunnel.close() }
            return null // any thrown auth error ⇒ fail-closed reject, never a false grant
        }

        return if (outcome is OperatorAuthOutcome.Granted) {
            tunnel
        } else {
            // Rejected / DeviceNotEnrolled / UvFailed / EnrollCodesUnavailable — the session surfaces the real cause
            // for the CONTROL connection; a pool tunnel simply fails-closed (no tunnel) so the transport RSTs.
            runCatching { tunnel.close() }
            null
        }
    }
}
