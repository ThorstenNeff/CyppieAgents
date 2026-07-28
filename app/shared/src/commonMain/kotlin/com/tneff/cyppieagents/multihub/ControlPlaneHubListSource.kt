package com.tneff.cyppieagents.multihub

import com.tneff.cyppieagents.connect.ControlPlaneClient
import com.tneff.cyppieagents.connect.HubDescriptor
import com.tneff.cyppieagents.connect.StubControlPlaneClient

/**
 * CYP-861 (Compose-M4 Mirror, list-live slice) — the **real hub-LIST source**: adapts the control-plane
 * ([ControlPlaneClient.hubs] = seam S-1 `GET /api/cp/hubs`) onto the display-only [HubListSource] the
 * [HubListHolder] consumes. This is the ONLY live wiring M4 adds: it flips the CYP-856 dormant `AgentShell`
 * `hubListSource` seam from stub → the real registered-hub list.
 *
 * **Display-only — arming stays DARK (§9.3).** This adapter fetches the hub LIST and nothing more: no
 * descriptor→endpoint derivation, no connect, no switch-effect. The holder maps a thrown
 * [com.tneff.cyppieagents.connect.ControlPlaneUnreachableException] to a fail-closed load-error (never a
 * misleading empty). The active-hub pointer / `onSwitch` / observed trust are the arming cluster (Compose-M3),
 * left dark at the mount.
 */
class ControlPlaneHubListSource(
    private val controlPlane: ControlPlaneClient,
) : HubListSource {
    override suspend fun hubs(): List<HubDescriptor> = controlPlane.hubs()
}

/**
 * CYP-861 — the **env-gate** that keeps prod byte-identical off-CP. `defaultControlPlaneClient` returns a real
 * [com.tneff.cyppieagents.connect.HttpControlPlaneClient] only when `CYPPIE_CP_BASE_URL` is set (jvm), else the
 * INERT [StubControlPlaneClient]. Mounting the switcher against the stub would show a spurious "Keine Hubs"
 * (or a stub-error) bar in every non-CP environment — NOT byte-identical. So: a [StubControlPlaneClient] resolves
 * to **null** → the CYP-856 `AgentShell` gate stays DORMANT (no bar); a real client → a live
 * [ControlPlaneHubListSource]. This is the single decision that flips the seam dormant↔live.
 */
fun hubListSourceFor(controlPlane: ControlPlaneClient): HubListSource? =
    if (controlPlane is StubControlPlaneClient) null else ControlPlaneHubListSource(controlPlane)
