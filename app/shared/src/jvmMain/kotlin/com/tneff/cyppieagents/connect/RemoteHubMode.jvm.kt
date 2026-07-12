package com.tneff.cyppieagents.connect

import com.tneff.cyppieagents.net.hub.noise.NoiseJavaClientTransport
import com.tneff.cyppieagents.net.hub.operator.ClientOperatorAuth
import com.tneff.cyppieagents.net.hub.operator.CpJwtProvider
import com.tneff.cyppieagents.net.hub.operator.KeystoreOperatorDeviceKeyStore
import com.tneff.cyppieagents.net.hub.operator.NonceGenerator
import com.tneff.cyppieagents.net.hub.operator.OperatorPopBuilder
import com.tneff.cyppieagents.net.hub.operator.UserVerification
import com.tneff.cyppieagents.net.hub.operator.UvOutcome
import com.tneff.cyppieagents.net.hub.remote.RelayDialer
import com.tneff.cyppieagents.net.hub.trust.RegistryPresentedHubKeySource
import com.tneff.cyppieagents.net.hub.trust.PendingOobConfirmations
import com.tneff.cyppieagents.net.hub.trust.TofuHubTrust
import com.tneff.cyppieagents.net.hub.trust.defaultPinnedHubStore
import java.security.SecureRandom

/** jvm: the remote-mode flag reads `CYP_REMOTE_HUB` (off unless explicitly `true`). Off-default. */
actual fun remoteHubEnabled(): Boolean =
    System.getenv("CYP_REMOTE_HUB")?.equals("true", ignoreCase = true) == true

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
                        keyPair = KeystoreOperatorDeviceKeyStore.generateDeviceKey(),
                    ),
                    nonceGenerator = secureRandomNonceGenerator,
                ),
                cpJwtProvider = CpJwtProvider { _, _ -> null }, // runway #4: HttpCpJwtProvider wires at S-J/CYP-514 ⇒ fail-closed until then
            ),
            scope = scope,
        )
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
