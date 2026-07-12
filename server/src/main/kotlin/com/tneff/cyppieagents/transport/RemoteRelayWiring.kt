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

    fun build(
        config: RemoteTransportConfig,
        httpClient: HttpClient,
        /** CYP-521: obtains the rendezvous id fresh from the CP register at dial-time (the epoch id), not a static env. */
        rendezvousId: suspend () -> String?,
        /** The hub's X25519 static PRIVATE scalar (S-C `hub.dhKey` from the SecretStore) — the NK responder static. */
        dhStaticPrivate: ByteArray,
        loopbackPort: Int,
        gate: Rr3TunnelGate,
        /** CYP-484 — the live-session registry (revocation teardown) + the authenticated operator + the passive TTL. */
        registry: TunnelSessionRegistry,
        operatorId: String,
        sessionTtlMs: Long,
        scope: CoroutineScope,
    ): RelayConnector = NoiseRelayConnector(
        config = config,
        dialer = WebSocketRelayDialer(httpClient, rendezvousId),
        terminator = NoiseJavaServerTerminator(dhStaticPrivate),
        tunnelHandler = Rr3AuthenticatedTunnelHandler(
            authorize = gate::authorize,
            bridge = LoopbackBridge(loopbackPort)::bridge,
            registry = registry,
            operatorId = operatorId,
            sessionTtlMs = sessionTtlMs,
        )::handle,
        scope = scope,
    )
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
    // CYP-484 — Op-Session-TTL (passive, minutes). Optional override CYPPIE_OP_SESSION_TTL_MIN; else the single-sourced default.
    val sessionTtlMs = env("CYPPIE_OP_SESSION_TTL_MIN")?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 60_000L }
        ?: RemoteRelayWiring.DEFAULT_OP_SESSION_TTL_MS

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
    )
    val httpClient = httpClientFactory() // shared: the relay WS dial AND the CP rendezvous-register POST
    val registrar = HubRendezvousRegistrar(cpBaseUrl = cpUrl, http = httpClient, hubId = hubIdentity.hubId, operatorBearer = { cpOperatorToken })
    return RemoteRelayWiring.build(
        config = RemoteTransportConfig(enabled = true, relayUrl = relayUrl),
        httpClient = httpClient,
        rendezvousId = { registrar.register() }, // CYP-521: fresh epoch id from the CP register, at dial-time
        dhStaticPrivate = dhPriv,
        loopbackPort = loopbackPort,
        gate = gate,
        registry = TunnelSessionRegistry(),
        operatorId = operatorId,
        sessionTtlMs = sessionTtlMs,
        scope = scope,
    )
}
