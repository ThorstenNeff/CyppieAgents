package com.tneff.cyppieagents.transport

import com.tneff.cyppieagents.CommJson
import com.tneff.cyppieagents.auth.CpJwtVerifier
import com.tneff.cyppieagents.auth.operator.OperatorAssertionVerifier
import com.tneff.cyppieagents.auth.operator.OperatorDeviceStore
import com.tneff.cyppieagents.controlplane.HubRendezvousRegistrar
import com.tneff.cyppieagents.crypto.HubIdentity
import com.tneff.cyppieagents.crypto.HubIdentityProvisioner
import com.tneff.cyppieagents.crypto.SecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import java.util.Base64

/**
 * CYP-459 (S3) — assembles the **live** RR3-authenticated remote relay connector from the boot pieces. Used ONLY
 * behind the Phase-2-Remote-GO gate in `bootPlatform`; the default boot passes [InertRelayConnector] (INERT — the
 * hub never dials, the current server is unchanged). The composite `tunnelHandler` = [Rr3AuthenticatedTunnelHandler]
 * (the RR3 gate → the loopback bridge), so a tunnelled connection is authenticated (CpJwt + PoP vs live `h`) before
 * any byte reaches a route, while the bridge stays a dumb byte-pump (T2).
 */
object RemoteRelayWiring {
    /** CYP-484 — the default Op-Session-TTL (passive session lifetime, Decision 4 "kurze TTL Minuten"), single-sourced. */
    const val DEFAULT_OP_SESSION_TTL_MS: Long = 15 * 60_000L

    /**
     * CYP-563 — the SINGLE SOURCE for the effective Op-Session-TTL: the optional `CYPPIE_OP_SESSION_TTL_MIN` override
     * (minutes, must be > 0) else [DEFAULT_OP_SESSION_TTL_MS]. BOTH legs of the `min(ticket-exp, tunnel-cap)` derive
     * from this ONE resolver — the hub tunnel-cap ([buildRemoteTransport] `sessionTtlMs`) AND the CP hubTicket `exp`
     * ([com.tneff.cyppieagents.controlplane.buildHubTicketMinter] → `LiveHubTicketMinter.ttlMs`) — so they cannot
     * drift when the override is set (previously the tunnel-cap honored the env but the ticket leg was hard-wired to
     * the default constant → drift; the CYP-503 single-source claim now holds for the override case too).
     */
    fun resolveOpSessionTtlMs(env: (String) -> String?): Long =
        env("CYPPIE_OP_SESSION_TTL_MIN")?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 60_000L }
            ?: DEFAULT_OP_SESSION_TTL_MS

    /**
     * CYP-596 — the SINGLE SOURCE for the enroll SavedAck window: the optional `CYPPIE_ENROLL_SAVEDACK_TIMEOUT_SEC`
     * override (seconds, must be > 0) else [Rr3TunnelGate.DEFAULT_SAVEDACK_TIMEOUT_MS] (5 min). The window must comfortably
     * exceed the time a human needs to SAVE the revealed backup codes before confirming — the original 30s discarded a
     * slow-but-legit operator (dogfood 2026-07-15) → fail-closed re-enroll loop. Ops-tunable without a rebuild.
     */
    fun resolveSavedAckTimeoutMs(env: (String) -> String?): Long =
        env("CYPPIE_ENROLL_SAVEDACK_TIMEOUT_SEC")?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1_000L }
            ?: Rr3TunnelGate.DEFAULT_SAVEDACK_TIMEOUT_MS

    fun build(
        config: RemoteTransportConfig,
        httpClient: HttpClient,
        /** CYP-536: the epoch-derived rendezvous **SET** (fresh from the CP register, cached anti-rotation). The
         *  manager runs one persistent responder per id in the set (C4, develop `20db04ad`). */
        rendezvousIdSet: suspend () -> List<String>?,
        /** The hub's X25519 static PRIVATE scalar (S-C `hub.dhKey` from the SecretStore) — the NK responder static. */
        dhStaticPrivate: ByteArray,
        loopbackPort: Int,
        gate: Rr3TunnelGate,
        /** CYP-484 — the live-session registry (revocation teardown) + the authenticated operator + the passive TTL. */
        registry: TunnelSessionRegistry,
        operatorId: String,
        sessionTtlMs: Long,
        scope: CoroutineScope,
        /** CYP-536 — the per-operator tunnel cap (= the CP set size). Single-sourced [RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP]. */
        poolCap: Int = com.tneff.cyppieagents.controlplane.RelayRendezvous.DEFAULT_TUNNEL_POOL_CAP,
    ): RelayConnector {
        // CYP-536 — ONE gate + ONE registry + ONE handler, SHARED by all N per-id responders. This is load-bearing:
        //  · one gate ⇒ one nonce ledger (single-use nonces enforced ACROSS all N tunnels, R2) + one first-enroll lock;
        //  · one registry + one operatorId ⇒ a revocation fans out to EVERY one of the operator's N tunnels (WS6
        //    axis 1b — `TunnelSessionRegistry.revokeOperator` closes all matching sessions, proven for N by CYP-484).
        // Only the per-tunnel dialer (fixed rendezvous-id) and the NK terminator are built per responder (each Noise
        // handshake is independent). The bridge/gate are stateless per invocation, so sharing is correct.
        val handler = Rr3AuthenticatedTunnelHandler(
            authorize = gate::authorize,
            bridge = LoopbackBridge(loopbackPort)::bridge,
            registry = registry,
            operatorId = operatorId,
            sessionTtlMs = sessionTtlMs,
        )
        return ConcurrentRelayResponderManager(
            source = SessionRendezvousSource { rendezvousIdSet() },
            responderFor = { id ->
                NoiseRelayConnector(
                    config = config,
                    dialer = WebSocketRelayDialer(httpClient) { id }, // this responder dials ONLY its own rendezvous-id
                    terminator = NoiseJavaServerTerminator(dhStaticPrivate), // fresh per responder (independent handshake)
                    tunnelHandler = handler::handle, // SHARED → shared registry (revocation fanout) + shared nonce ledger
                    scope = scope,
                )
            },
            poolCap = poolCap,
            scope = scope,
        )
    }
}

/**
 * CYP-459 (S3) — the Phase-2-Remote-GO boot gate: build the LIVE [NoiseRelayConnector] (with the RR3 gate) **only**
 * when the gate env (`CYPPIE_REMOTE_RELAY_URL`) is set AND local-hub custody ([hubIdentity] / [hubSecretStore] /
 * [operatorDeviceStore]) plus the CP-pin config are ALL present; **fail-closed to [InertRelayConnector] on ANY gap**
 * (never a half-configured remote dial). INERT by default → the current server is unchanged. [env] and
 * [httpClientFactory] are injectable so the wiring is unit-testable without real env/network.
 */
fun buildRemoteTransport(
    loopbackPort: Int,
    hubIdentity: HubIdentity?,
    hubSecretStore: SecretStore?,
    operatorDeviceStore: OperatorDeviceStore?,
    scope: CoroutineScope,
    env: (String) -> String? = System::getenv,
    // CYP-521: one client for BOTH the outbound relay WS dial AND the CP rendezvous-register POST (JSON).
    httpClientFactory: () -> HttpClient = { HttpClient(CIO) { install(WebSockets); install(ContentNegotiation) { json(CommJson) } } },
    /** CYP-525 GE5/GE7 — the combined crash-atomic finalize store (device anchor + code-hashes). When present the gate
     *  runs the ratified provisional→SavedAck→finalize flow; `null` keeps the pre-GE5 immediate deviceStore path. */
    finalizedStore: com.tneff.cyppieagents.auth.operator.FinalizedEnrollmentStore? = null,
): RelayConnector {
    val relayUrl = env("CYPPIE_REMOTE_RELAY_URL")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    // The live path REQUIRES local-hub custody + the full CP-pin config; any gap → INERT (fail-closed).
    if (hubIdentity == null || hubSecretStore == null || operatorDeviceStore == null) return InertRelayConnector
    val operatorId = env("CYPPIE_OPERATOR_ID")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    val cpIssuer = env("CYPPIE_CP_ISSUER")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    val cpKid = env("CYPPIE_CP_KID")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    val cpPub = env("CYPPIE_CP_PUBKEY")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        ?: return InertRelayConnector
    val rpId = env("CYPPIE_OPERATOR_RP_ID")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    // CYP-521: the hub dial-side rendezvous id is now obtained from the CP register (the epoch id), NOT a static env.
    // Needs the CP URL + the operator bearer (like the CYP-512 admit) — a static CYPPIE_REMOTE_RENDEZVOUS is GONE.
    val cpUrl = env("CYPPIE_CP_URL")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    val cpOperatorToken = env("CYPPIE_CP_OPERATOR_TOKEN")?.takeIf { it.isNotBlank() } ?: return InertRelayConnector
    val dhPriv = hubSecretStore.get(HubIdentityProvisioner.DH_KEY)
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        ?: return InertRelayConnector
    // CYP-484 / CYP-563 — Op-Session-TTL (passive, minutes) from the ONE shared resolver (single-sourced with the CP
    // hubTicket exp so the two legs of min(ticket-exp, tunnel-cap) cannot drift when CYPPIE_OP_SESSION_TTL_MIN is set).
    val sessionTtlMs = RemoteRelayWiring.resolveOpSessionTtlMs(env)

    val gate = Rr3TunnelGate(
        cpJwtVerifier = CpJwtVerifier(),
        operatorVerifier = OperatorAssertionVerifier(),
        deviceStore = operatorDeviceStore,
        config = Rr3Config(
            hubId = hubIdentity.hubId,
            pinnedOperatorId = operatorId,
            expectedIssuer = cpIssuer,
            cpPublicKey = { k -> if (k == cpKid) cpPub else null },
            expectedRpId = rpId,
        ),
        finalizedStore = finalizedStore, // CYP-525 GE5/GE7: the ratified provisional→finalize path (prod when wired)
        savedAckTimeoutMs = RemoteRelayWiring.resolveSavedAckTimeoutMs(env), // CYP-596: ops-tunable enroll SavedAck window (default 5 min)
    )
    val httpClient = httpClientFactory() // shared: the relay WS dial AND the CP rendezvous-register POST
    val registrar = HubRendezvousRegistrar(cpBaseUrl = cpUrl, http = httpClient, hubId = hubIdentity.hubId, operatorBearer = { cpOperatorToken })
    // CYP-536: register ONCE and CACHE the epoch-derived rendezvous SET; every one of the N responders (and their
    // reconnect loops) reuses the SAME cached set (anti-rotation — a re-register would rotate the epoch → a different
    // set → the client's resolved set no longer pairs). A failed/empty register isn't cached → retried.
    val cachedRendezvousSet = CachingRendezvousIdSet { registrar.registerSet() }
    return RemoteRelayWiring.build(
        config = RemoteTransportConfig(enabled = true, relayUrl = relayUrl),
        httpClient = httpClient,
        rendezvousIdSet = cachedRendezvousSet::get, // CYP-536 the epoch-derived N-set (cached once, anti-rotation)
        dhStaticPrivate = dhPriv,
        loopbackPort = loopbackPort,
        gate = gate,
        registry = TunnelSessionRegistry(),
        operatorId = operatorId,
        sessionTtlMs = sessionTtlMs,
        scope = scope,
    )
}
