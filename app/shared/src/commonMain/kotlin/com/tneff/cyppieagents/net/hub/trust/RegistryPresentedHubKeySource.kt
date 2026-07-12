package com.tneff.cyppieagents.net.hub.trust

import com.tneff.cyppieagents.connect.ControlPlaneClient
import com.tneff.cyppieagents.connect.HubDescriptor

/**
 * CYP-495 (Client-Remote-Runway #2) — the real [PresentedHubKeySource]: a hub's presented Noise static comes
 * from the control-plane registry's `dhPubKey` (base64, mirrored from the `:core` DTO, CYP-481), decoded
 * straight-through to raw bytes. This replaces the CYP-486 seam-#2 stub ([MapPresentedHubKeySource]) → the
 * TOFU pin / OOB fingerprint (CYP-478 / CYP-482) become **real**.
 *
 * **Fail-closed** (reuses the CYP-478 [decodePin] guard: base64 → exactly [HUB_STATIC_KEY_SIZE] bytes, else
 * `null`): a missing / non-base64 / wrong-sized `dhPubKey` yields `null`, so [TofuHubTrust] cannot handshake
 * (never a blind connect). The registry is a **pure introducer** — the OOB-confirmed pin, not this presented
 * value, is the source of truth (CI-1); this only supplies the candidate to pin/compare.
 */
class RegistryPresentedHubKeySource(
    private val lookup: suspend (hubId: String) -> HubDescriptor?,
) : PresentedHubKeySource {

    override suspend fun presentedStatic(hubId: String): ByteArray? =
        lookup(hubId)?.let { decodePin(it.dhPubKey) }

    companion object {
        /** For a session already scoped to a single hub (the `RemoteHubSessionFactory` case). */
        fun of(hub: HubDescriptor): RegistryPresentedHubKeySource =
            RegistryPresentedHubKeySource { id -> hub.takeIf { it.hubId == id } }

        /** Resolve against the live CP registry — find the hub in `GET /hubs`. */
        fun fromControlPlane(controlPlane: ControlPlaneClient): RegistryPresentedHubKeySource =
            RegistryPresentedHubKeySource { id -> controlPlane.hubs().firstOrNull { it.hubId == id } }
    }
}
