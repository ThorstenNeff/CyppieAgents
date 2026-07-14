package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.operator.ChannelBinding
import com.tneff.cyppieagents.net.hub.operator.ClientOperatorAuth
import com.tneff.cyppieagents.net.hub.operator.CpJwtProvider
import com.tneff.cyppieagents.net.hub.operator.HttpCpJwtProvider
import com.tneff.cyppieagents.net.hub.operator.KeystoreOperatorDeviceKeyStore
import com.tneff.cyppieagents.net.hub.operator.NonceGenerator
import com.tneff.cyppieagents.net.hub.operator.OperatorPopBuilder
import com.tneff.cyppieagents.net.hub.operator.PersistentOperatorDeviceKey
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.operator.coreChannelBinding
import com.tneff.cyppieagents.net.hub.pool.NoisePoolTunnelDialer
import com.tneff.cyppieagents.net.hub.pool.PooledTunnelSource
import com.tneff.cyppieagents.net.hub.relay.HttpRendezvousResolver
import com.tneff.cyppieagents.net.hub.relay.KtorWsRelayConnector
import com.tneff.cyppieagents.net.hub.relay.RendezvousRelayDialer
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.sharedWsHttpClient
import com.tneff.cyppieagents.net.hub.trust.RegistryPresentedHubKeySource
import com.tneff.cyppieagents.net.hub.trust.PendingOobConfirmations
import com.tneff.cyppieagents.net.hub.trust.TofuHubTrust
import com.tneff.cyppieagents.net.hub.trust.defaultPinnedHubStore
import io.ktor.client.HttpClient
import java.security.SecureRandom

/** jvm: the remote-mode flag reads `CYP_REMOTE_HUB` (off unless explicitly `true`). Off-default. */
actual fun remoteHubEnabled(): Boolean =
    System.getenv("CYP_REMOTE_HUB")?.equals("true", ignoreCase = true) == true

/**
 * jvm: the LIVE components factory — composed from env config, **fail-closed to INERT** when unconfigured. Needs
 * BOTH `CYPPIE_CP_BASE_URL` (the same-origin CP) and `CYPPIE_REMOTE_RELAY_URL` (the relay-server activation gate);
 * absent ⇒ `null` ⇒ the connect path stays the stub feed. The operator-authed client is [sharedWsHttpClient]
 * ([operatorToken] rides as the same-origin session; the resolver/cpJwt also send it as `Bearer`).
 */
actual fun defaultRemoteComponentsFactory(operatorToken: () -> String?): RemoteConnectComponentsFactory? {
    val cpBaseUrl = System.getenv("CYPPIE_CP_BASE_URL")?.takeIf { it.isNotBlank() } ?: return null
    if (System.getenv("CYPPIE_REMOTE_RELAY_URL").isNullOrBlank()) return null // no relay server ⇒ INERT (fail-closed)
    val client = sharedWsHttpClient(operatorToken)
    return liveRemoteConnectComponentsFactory(cpBaseUrl, client, { operatorToken() }, client)
}

/**
 * jvm: the [ControlPlaneClient] — env-gated live [HttpControlPlaneClient] when `CYPPIE_CP_BASE_URL` is set, else the
 * [StubControlPlaneClient] (INERT — the CYP-419 stub default, byte-identical to today). The operator session rides
 * as `Bearer` on the [sharedWsHttpClient] (same source/auth as [defaultRemoteComponentsFactory]). Only reached when
 * [remoteHubEnabled]; the live swap + deploy stay an Auftraggeber GO — off unless the env is configured.
 */
actual fun defaultControlPlaneClient(operatorToken: () -> String?): ControlPlaneClient {
    val cpBaseUrl = System.getenv("CYPPIE_CP_BASE_URL")?.takeIf { it.isNotBlank() } ?: return StubControlPlaneClient()
    return HttpControlPlaneClient(sharedWsHttpClient(operatorToken), cpBaseUrl, { operatorToken() })
}

/**
 * jvm: the **real but INERT** remote assembly — the honest wiring topology. The real Noise transport, TOFU
 * trust, and operator-auth are all constructed; the still-gated pieces are explicit **fail-closed seams** (the
 * CYP-486 Client-Remote-Runway), so even a flipped flag connects to **nothing**:
 *  - runway #1 [RelayDialer] → throws (no client rendezvous yet) ⇒ every connect fails at dial (RelayUnreachable);
 *  - runway #2 presented-key → **FILLED (CYP-495)**: [RegistryPresentedHubKeySource] decodes the hub's `dhPubKey`
 *    (base64→32B, fail-safe → null on absent/malformed) ⇒ trust pins/compares a real key;
 *  - runway #4 [CpJwtProvider] → `null` (S-K hubTicket deferred) ⇒ operator-auth fail-closed.
 * The nonce source (runway #3) IS built here (jvm `SecureRandom`). Never a fake success — real only when the runway lands.
 */
actual fun defaultRemoteHubSessionFactory(): RemoteHubSessionFactory? =
    RemoteHubSessionFactory { hub, scope ->
        // CYP-504: delegate to the seam-injectable builder with the GATED prod seams (per-hub TOFU trust stays
        // in this closure). Behaviour is byte-identical to the previous inline assembly — still fails closed at dial.
        buildRemoteHubSession(
            hubId = hub.hubId,
            transport = NoiseJavaClientTransport(),
            dialer = gatedRelayDialer,
            trust = TofuHubTrust(
                presentedKeys = RegistryPresentedHubKeySource.of(hub), // CYP-495: real dhPubKey (runway #2 filled)
                store = defaultPinnedHubStore(),
                confirmer = PendingOobConfirmations(),
            ),
            authenticator = ClientOperatorAuth(
                popBuilder = OperatorPopBuilder(
                    store = KeystoreOperatorDeviceKeyStore(
                        userVerification = deferredUserVerification,
                        keyPair = persistentDeviceKey.loadOrGenerate(),
                    ),
                    nonceGenerator = secureRandomNonceGenerator,
                ),
                cpJwtProvider = CpJwtProvider { _, _ -> null }, // runway #4: HttpCpJwtProvider wires at S-J/CYP-514 ⇒ fail-closed until then
            ),
            scope = scope,
        )
    }

/**
 * CYP-513 — the **LIVE** per-connect remote components (activation). Composes the real remote-connect stack:
 *  - **①② shared trust+coordinator** — ONE `of(hub)` + ONE `PendingOobConfirmations` via [buildSharedHubTrustComponents]
 *    (display == pinned; approve/reject wake the trust's own waiter);
 *  - **RelayDialer swapped live** — [RendezvousRelayDialer] over CP rendezvous ([HttpRendezvousResolver]) + the relay
 *    WS ([KtorWsRelayConnector]); fails closed (typed cause) if the CP/relay is unreachable;
 *  - **cpJwt live** — [HttpCpJwtProvider] with the real [coreChannelBinding] (CYP-514) + the operator session Bearer.
 *
 * Wired only from App.kt when remote mode is on (flag-gated); the relay-server operation is the Auftraggeber-GO.
 * Never a fake success — every seam fails closed if its live endpoint isn't reachable.
 */
fun liveRemoteConnectComponentsFactory(
    cpBaseUrl: String,
    cpHttpClient: HttpClient,
    operatorToken: suspend () -> String?,
    relayWsClient: HttpClient,
    channelBinding: ChannelBinding = coreChannelBinding(),
): RemoteConnectComponentsFactory = RemoteConnectComponentsFactory { hub, scope ->
    val shared = buildSharedHubTrustComponents(hub, defaultPinnedHubStore()) // ①² one of(hub)+pending into both
    // CYP-525 §2: ONE enroll confirmer shared between the session's ClientOperatorAuth (which calls it + suspends on
    // firstEnroll) and the RemoteConnectComponents (which the VM surfaces as RevealCodes) — what the operator confirms
    // IS what gates the SavedAck the session sends.
    val enrollConfirm = LiveEnrollConfirmCoordinator()
    // Hoisted so the CYP-537 N-tunnel pool SHARES them with the session: pool tunnels ride the same TOFU pin
    // ([shared.trust]) + the same enrolled device ([operatorAuth]'s durable key) the session's connect established.
    val noiseTransport = NoiseJavaClientTransport()
    val operatorAuth = ClientOperatorAuth(
        popBuilder = OperatorPopBuilder(
            store = KeystoreOperatorDeviceKeyStore(
                userVerification = deferredUserVerification,
                keyPair = persistentDeviceKey.loadOrGenerate(),
            ),
            nonceGenerator = secureRandomNonceGenerator,
        ),
        cpJwtProvider = HttpCpJwtProvider(cpHttpClient, cpBaseUrl, operatorToken, channelBinding),
        enrollConfirmer = enrollConfirm,
    )
    // Shared by the session's single-tunnel dialer AND the CYP-537 pool: ONE resolver (CP rendezvous, CYP-536
    // epoch-set) + ONE id-aware relay connector (stateless HTTP/WS). The session dials the base id (rendezvousIds[0]);
    // the pool dials the rest (id_1..id_{cap-1}, `NoisePoolTunnelDialer.rendezvousSet()` = `drop(1)`) — no collision.
    val rendezvousResolver = HttpRendezvousResolver(cpHttpClient, cpBaseUrl, operatorToken)
    val relayConnector = KtorWsRelayConnector(relayWsClient)
    val session = buildRemoteHubSession(
        hubId = hub.hubId,
        transport = noiseTransport,
        dialer = RendezvousRelayDialer(resolver = rendezvousResolver, connector = relayConnector),
        trust = shared.trust,
        authenticator = operatorAuth,
        scope = scope,
    )
    // CYP-537 (M2 Option A, WS2) — the N-tunnel pool, the F-M2-1 fix. It shares the session's transport/trust/
    // authenticator (pool tunnels ride the pin + enrolled device) AND the live rendezvous resolver + connector
    // (C4, CYP-536): `rendezvousSet()` resolves the CP epoch-set once and dials id_1..id_{cap-1}; the ids stay
    // CP-derived + opaque (never re-derived). Real against Backend's live WS1 N-responder; still gated OFF by env
    // (`CYPPIE_CP_BASE_URL` + `CYPPIE_REMOTE_RELAY_URL`) + `CYP_REMOTE_HUB` — the pool is only reached on a live
    // remote connect. The pool mechanics (cap, lifecycle, C3 state, H7 backpressure) are unit-gated (`net/hub/pool`).
    val tunnelPool = PooledTunnelSource(
        dialer = NoisePoolTunnelDialer(
            hubId = hub.hubId,
            transport = noiseTransport,
            trust = shared.trust,
            authenticator = operatorAuth,
            resolver = rendezvousResolver, // WS1 live: resolves the CYP-536 epoch N-set (rendezvousIds)
            connector = relayConnector,    // id-aware relay open (X-Cyppie-Rendezvous, role: client)
        ),
        nowMs = { System.currentTimeMillis() },
    )
    RemoteConnectComponents(session, shared.oobConfirm, enrollConfirm, tunnelPool)
}

/** Runway #1: no client relay/rendezvous dialer yet → fail-closed at dial (RelayUnreachable), never connects. */
private val gatedRelayDialer = RelayDialer {
    error("client relay rendezvous is not available yet (CYP-486 runway #1) — remote connect fails closed")
}

/** No UV UI in the headless assembly; never reached (dial fails first). Fail-closed ⇒ Unavailable. */
private val deferredUserVerification = UserVerification { UvOutcome.Unavailable }

/** Runway #3: the jvm PoP nonce source (`SecureRandom`) — the one small runway item built inline. */
private val secureRandomNonceGenerator = NonceGenerator {
    ByteArray(32).also { SecureRandom().nextBytes(it) }
}

/**
 * CYP-525 Inc 2: the ONE durable operator device-key (DEVICE_SECURE, `~/.cyppie/operator-device.key`) —
 * `loadOrGenerate()` persists+reuses one Ed25519 key across launches, replacing the fresh-per-launch
 * `generateDeviceKey()` that never matched the hub's enrolled anchor.
 */
private val persistentDeviceKey = PersistentOperatorDeviceKey(PersistentOperatorDeviceKey.defaultKeyFile())
