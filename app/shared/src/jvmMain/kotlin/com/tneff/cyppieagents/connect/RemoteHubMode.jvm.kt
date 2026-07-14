package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.operator.CachingUserVerification
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
import com.tneff.cyppieagents.net.hub.operator.vault.BcArgon2PassphraseKdf
import com.tneff.cyppieagents.net.hub.operator.vault.DecryptedKeyHold
import com.tneff.cyppieagents.net.hub.operator.vault.JceAead
import com.tneff.cyppieagents.net.hub.operator.vault.OperatorSecretVault
import com.tneff.cyppieagents.net.hub.operator.vault.OwnerOnlyVaultStore
import com.tneff.cyppieagents.net.hub.operator.vault.PassphraseUserVerification
import com.tneff.cyppieagents.net.hub.operator.vault.VaultOperatorDeviceKeyStore
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
    // CYP-542 / B1 — the operator passphrase prompt (the CYP-460 dialog). Default = **fail-closed** (returns `null` ⇒
    // Denied(CANCELLED)) so prod stays INERT until App.kt wires the real dialog prompt (the isolated INERT→real kip).
    passphrasePrompt: com.tneff.cyppieagents.net.hub.operator.vault.PassphrasePrompt = failClosedPassphrasePrompt,
    // CYP-537 F⑥-1 test-seam (PO ruling (b), Assist-gated): the RAW UV under the shared [CachingUserVerification].
    // **`null` (prod default) ⇒ the real [PassphraseUserVerification]** (built per-connect over the vault+prompt+hold);
    // a non-null override injects a VERIFYING raw UV for the joint e2e (the "1 UV for N" positive path). Same
    // default→prod / override→test discipline as `connectorFactory`/`TunnelSource`; the override never reaches prod.
    rawUserVerification: UserVerification? = null,
): RemoteConnectComponentsFactory = RemoteConnectComponentsFactory { hub, scope ->
    val shared = buildSharedHubTrustComponents(hub, defaultPinnedHubStore()) // ①² one of(hub)+pending into both
    // CYP-525 §2: ONE enroll confirmer shared between the session's ClientOperatorAuth (which calls it + suspends on
    // firstEnroll) and the RemoteConnectComponents (which the VM surfaces as RevealCodes) — what the operator confirms
    // IS what gates the SavedAck the session sends.
    val enrollConfirm = LiveEnrollConfirmCoordinator()
    // Hoisted so the CYP-537 N-tunnel pool SHARES them with the session: pool tunnels ride the same TOFU pin
    // ([shared.trust]) + the same enrolled device ([operatorAuth]'s durable key) the session's connect established.
    val noiseTransport = NoiseJavaClientTransport()
    // CYP-537 F⑥-1 (Reviewer / CYP-538 WS3): the N pool tunnels each PoP-auth over their own `h_i` via this ONE
    // shared [operatorAuth] → its ONE store → this ONE [CachingUserVerification]. The session's control tunnel and
    // the pool's N workspace tunnels therefore share a SINGLE bounded-window UV cache: the first authenticate prompts
    // (a real UV ceremony), the rest within the window ride the cache ⇒ **1 UV prompt for N tunnels** (0 extra
    // prompts — critical for dogfood). Security unchanged (each PoP is fresh over its own `h_i`, fail-closed; a denial
    // is never cached). O5: [nowMs] is **monotonic** (`nanoTime`) so a wall-clock adjustment can't widen the window.
    // CYP-542 / B1 — the **prod-flip**: the operator device key is now the passphrase-sealed [OperatorSecretVault]
    // (Argon2id KEK → AES-GCM), replacing the plaintext CYP-525 custody. Clocks are split by intent: the vault uses
    // **wall-clock** (`currentTimeMillis`) so its persisted rate-limit lockout survives a restart; the key-hold + the
    // UV cache use a **monotonic** clock (O5) so a wall-clock jump can't widen the ≤120s reuse window.
    val monotonicMs = { System.nanoTime() / 1_000_000 }
    val vault = OperatorSecretVault(
        store = OwnerOnlyVaultStore(defaultVaultFile()),
        kdf = BcArgon2PassphraseKdf(),
        aead = JceAead(),
        nowMs = { System.currentTimeMillis() },
    )
    val keyHold = DecryptedKeyHold(nowMs = monotonicMs)
    // The RAW UV: prod = the real [PassphraseUserVerification] (prompt → vault.open → key-hold); the test-seam override
    // ([rawUserVerification] non-null) injects a verifying UV for the joint e2e. **Prod stays fail-closed until a real
    // [passphrasePrompt] is wired at App.kt** (default = a fail-closed prompt ⇒ no prompt ⇒ Denied(CANCELLED)) — the
    // INERT→real kip is App.kt providing the CYP-460 dialog prompt, isolated from this store-swap.
    val rawUv = rawUserVerification
        ?: PassphraseUserVerification(vault, passphrasePrompt, keyHold, monotonicMs, OPERATOR_UV_REUSE_WINDOW_MS)
    // F⑥-1 / CYP-547: ONE shared [CachingUserVerification] — session-auth AND pool-auth run through THIS instance
    // (store.userVerification IS it; the pool's authenticator IS the same operatorAuth) ⇒ 1-UV-for-N (Tester drives
    // this exact instance via [RemoteConnectComponents.operatorUvCache]). The store-swap must NOT split it into two.
    val cachingUv = buildOperatorUvCache(rawUv)
    val operatorAuth = ClientOperatorAuth(
        popBuilder = OperatorPopBuilder(
            // B1 store-swap: vault-backed (decrypts the key behind the UV into the bounded hold, then Ed25519-signs)
            // instead of holding a plaintext keyPair. store.userVerification IS the shared cachingUv (no wiring drift).
            store = VaultOperatorDeviceKeyStore(vault, cachingUv, keyHold),
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
    RemoteConnectComponents(session, shared.oobConfirm, enrollConfirm, tunnelPool, operatorUvCache = cachingUv)
}

/** CYP-542 / B1 — the passphrase-sealed operator device-key vault file (DEVICE_SECURE intent), sibling of the CYP-525
 *  key file under `~/.cyppie/`. Owner-only + atomic writes are the [OwnerOnlyVaultStore]'s job. */
private fun defaultVaultFile(): java.nio.file.Path =
    java.nio.file.Paths.get(System.getProperty("user.home"), ".cyppie", "operator-vault")

/** Prod default until App.kt wires the CYP-460 dialog: no prompt ⇒ `null` ⇒ Denied(CANCELLED) ⇒ fail-closed (INERT). */
private val failClosedPassphrasePrompt =
    com.tneff.cyppieagents.net.hub.operator.vault.PassphrasePrompt { null }

/** Runway #1: no client relay/rendezvous dialer yet → fail-closed at dial (RelayUnreachable), never connects. */
private val gatedRelayDialer = RelayDialer {
    error("client relay rendezvous is not available yet (CYP-486 runway #1) — remote connect fails closed")
}

/** No UV UI in the headless assembly; never reached (dial fails first). Fail-closed ⇒ Unavailable. */
private val deferredUserVerification = UserVerification { UvOutcome.Unavailable }

/**
 * CYP-537 F⑥-1 (CYP-538) — the bounded UV-reuse window for the shared [CachingUserVerification]. Long enough to span
 * the connect burst (the session's control-tunnel UV + the pool's N workspace-tunnel establishments, all within
 * seconds of CONNECTED) plus a little live churn, so it costs **one** prompt; short enough that reuse stays bounded
 * (a denial is never cached — fail-closed). Tunable (Reviewer may weigh the UX↔bound trade-off); 2 min is the
 * conservative-short default.
 */
private const val OPERATOR_UV_REUSE_WINDOW_MS: Long = 120_000L

/**
 * CYP-537 F⑥-1 — build the shared operator UV cache with the **prod** parameters: the bounded [OPERATOR_UV_REUSE_WINDOW_MS]
 * window + a **monotonic** millisecond clock (`nanoTime`, O5 — a wall-clock jump can't widen the window). Single-sourced
 * so the factory + the seam test exercise the SAME construction; `internal` so the test can pin prod-inert-vs-override.
 */
internal fun buildOperatorUvCache(rawUv: UserVerification): CachingUserVerification =
    CachingUserVerification(
        delegate = rawUv,
        reuseWindowMs = OPERATOR_UV_REUSE_WINDOW_MS,
        nowMs = { System.nanoTime() / 1_000_000 },
    )

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
