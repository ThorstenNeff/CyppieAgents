package com.tneff.cyppieagents.net.hub.pool

import com.tneff.cyppieagents.net.hub.noise.ClientNoiseTransport
import com.tneff.cyppieagents.net.hub.noise.NoiseTunnel
import com.tneff.cyppieagents.net.hub.noise.RelayChannel
import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthOutcome
import com.tneff.cyppieagents.net.hub.remote.OperatorAuthenticator
import com.tneff.cyppieagents.net.hub.remote.TrustResolution
import kotlin.coroutines.cancellation.CancellationException

/**
 * CYP-537 (M2 Option A, WS2) — the **real** [PoolTunnelDialer]. Two responsibilities, both C4-shaped:
 *
 *  - [rendezvousSet] delegates to the injected [rendezvousSetResolver] — the CP N-set of opaque, epoch-derived ids
 *    (`id_i = base64url(SHA-256(hubId ‖ epoch ‖ i))`, CP secret epoch). **Convergence seam** (co-located WS1↔WS2 on
 *    the Register/Resolve set-format): fail-closed `null` until Backend's CP-N-set endpoint lands ⇒ the pool is INERT.
 *  - [dial] establishes one additional workspace tunnel over a **specific** opaque id via [dialRendezvous] (the
 *    id-aware relay open — the connector is already id-keyed, the id now comes from the CP set, not a resolve), then
 *    runs the same crypto the session ran: resolve trust (Pinned) → Noise_NK against the pin → operator PoP.
 *
 * It is deliberately the **simpler** path than the session's `attemptConnect`: the session owns the ONE-time TOFU
 * first-use OOB confirm, the first-enroll reveal, reconnect/backoff and the RemoteSessionState UX; by the time the
 * pool dials (only AFTER the session reaches CONNECTED), trust is already **Pinned** and the device **enrolled**, so
 * this path needs neither OOB nor enroll — just a pinned handshake + a Granted PoP, else **fail-closed to `null`**.
 *
 * **Fail-closed, every step:** an id-dial throw, a `TrustResolution.Changed` (attack — NEVER a silent re-pin, CI-5),
 * a handshake failure, or any non-[OperatorAuthOutcome.Granted] outcome ⇒ `null` (the relay/tunnel is closed first).
 * [CancellationException] always propagates (a switch/leave must unwind, never be swallowed as a fail).
 *
 * **Auth cost (C1, WS3):** each [OperatorAuthenticator.authenticate] performs a WebAuthn user-verification; N tunnels
 * would mean N prompts. The **UV-per-session caching** is WS3's job — it lands as a UV-caching [authenticator] wrapped
 * around this same seam, so this dialer is unchanged when it arrives (it only ever consumes the `authenticate` seam).
 */
class NoisePoolTunnelDialer(
    private val hubId: String,
    private val transport: ClientNoiseTransport,
    private val trust: HubTrust,
    private val authenticator: OperatorAuthenticator,
    private val rendezvousSetResolver: suspend () -> List<String>?,
    private val dialRendezvous: suspend (rendezvousId: String) -> RelayChannel,
    private val prologue: ByteArray = ByteArray(0),
) : PoolTunnelDialer {

    override suspend fun rendezvousSet(): List<String>? = rendezvousSetResolver()

    override suspend fun dial(rendezvousId: String): NoiseTunnel? {
        // Open the relay for THIS specific CP-derived opaque id (C4): N distinct pairings, relay unchanged.
        val relay = try {
            dialRendezvous(rendezvousId)
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
