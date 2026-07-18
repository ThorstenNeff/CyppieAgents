package com.tneff.cyppieagents.net.hub.trust

import com.tneff.cyppieagents.net.hub.remote.HubTrust
import com.tneff.cyppieagents.net.hub.remote.TrustResolution

/**
 * CYP-478 — the real [HubTrust] (the CYP-443 Slice-1 seam's Slice-3 impl): **TOFU pinning** of a hub's Noise
 * static (DH public key), SSH-`known_hosts` style, with a mandatory out-of-band fingerprint confirmation on
 * first use and a **hard block** on any later key change.
 *
 * Decision logic per [resolve] (Entscheidung 3 = Option X):
 *  - **First use** (no pin): confirm the presented key's [HubKeyFingerprint] OOB ([OobFingerprintConfirmer]),
 *    then — and only then — persist the pin and return [TrustResolution.FirstUse]. A reject/abort never pins
 *    (fail-closed; the confirmer throws → the session unwinds).
 *  - **Match** (pin == presented): return [TrustResolution.Pinned] → handshake against the pinned key (CI-1).
 *  - **Change** (pin != presented): return [TrustResolution.Changed] with the **pinned** key's fingerprint (the
 *    one the operator originally verified) → the session treats this as terminal ([RemoteFailure.TrustChanged],
 *    CI-5): **never a silent re-pin**, re-pinning is OOB-only (a fresh [PinnedHubStore.unpin] + first-use flow).
 *
 * The presented key comes from the control-plane registry (`dhPubKey`), which is a **pure introducer**: it is
 * NEVER trusted on its own — the pin, confirmed OOB, is the source of truth. A missing/garbled/wrong-sized
 * presented key fails closed ([HubStaticUnavailableException], retryable) rather than handshaking blind.
 */
class TofuHubTrust(
    private val presentedKeys: PresentedHubKeySource,
    private val store: PinnedHubStore,
    private val confirmer: OobFingerprintConfirmer,
) : HubTrust {

    override suspend fun resolve(hubId: String): TrustResolution {
        val presented = presentedKeys.presentedStatic(hubId)
            ?: throw HubStaticUnavailableException(hubId)
        if (presented.size != HUB_STATIC_KEY_SIZE) throw HubStaticUnavailableException(hubId)

        val pinned = store.pinnedKey(hubId)
        return when {
            pinned == null -> {
                // First use — poisoned-first-pin defence: OOB-confirm BEFORE we ever adopt the key.
                val fingerprint = HubKeyFingerprint.of(presented)
                confirmer.awaitConfirmation(hubId, fingerprint) // throws on reject → nothing is pinned
                store.pin(hubId, presented)                     // adopt ONLY after the confirm returns
                TrustResolution.FirstUse(presented, fingerprint)
            }
            pinned.contentEquals(presented) -> TrustResolution.Pinned(pinned)
            else -> TrustResolution.Changed(HubKeyFingerprint.of(pinned)) // CI-5 hard block; expected = the pinned fp
        }
    }
}

/**
 * The source of a hub's **presented** Noise static — the control-plane registry's `dhPubKey`, base64-decoded.
 * The real impl is [RegistryPresentedHubKeySource] (CYP-495): it reads `HubDescriptor.dhPubKey` (landed on the
 * registry DTO, CYP-481) and fail-safe-decodes it to 32 raw bytes. [MapPresentedHubKeySource] is the fixed-map
 * stub used only in tests. (The still-open runway is the *live* CP serving a non-empty `dhPubKey` — verified
 * against real hosts, not here.)
 */
fun interface PresentedHubKeySource {
    /** The hub's presented static, or `null` if the registry has no (readable) key for [hubId]. */
    suspend fun presentedStatic(hubId: String): ByteArray?
}

/** The registry has no usable static for the hub (missing/garbled/wrong-sized `dhPubKey`) → fail closed, retryable. */
class HubStaticUnavailableException(hubId: String) :
    Exception("no usable hub static (dhPubKey) for hub '$hubId' — cannot establish trust (fail-closed)")

/** A fixed-map [PresentedHubKeySource] to build/test against until the live registry `dhPubKey` is wired. */
class MapPresentedHubKeySource(private val keys: Map<String, ByteArray> = emptyMap()) : PresentedHubKeySource {
    override suspend fun presentedStatic(hubId: String): ByteArray? = keys[hubId]?.copyOf()
}
