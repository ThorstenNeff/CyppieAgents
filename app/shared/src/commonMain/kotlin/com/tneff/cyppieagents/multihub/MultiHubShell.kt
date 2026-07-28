package com.tneff.cyppieagents.multihub

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState

/**
 * CYP-873 (Compose Multi-Hub M4, mirror of web-ts CYP-854/`MultiHubShell`) — the **mount-host** that wires the M1–M5
 * units into the workspace. It holds the host orchestration — the ACTIVE pointer, per-hub trust PROVENANCE (M3,
 * CYP-865), and the ONE-ACTIVE connection ([ActiveHubConnection], CYP-865) — and renders Zone-1 (the [HubSwitcherBar],
 * fed [displayedTrust]) + Zone-2 (the [ConnectProgressionChrome], M5/CYP-866, fed [progressionStateFor] of the active
 * machine's state).
 *
 * ★ §3 RENDER-HONESTY PRE-ARMING (BINDING — mirror of the web-ts tooth). NOTHING arms here. The [HubConnector] is
 * INJECTED — production mounts the [idleHubConnector] (opens an idle handle whose machine rests at `null`, never
 * dials); the real dial connector arrives at arming (§9.3 / CYP-807-A5). Pre-arming, therefore:
 *  - the host records NO observation → the provenance stays empty → [displayedTrust] is UNKNOWN for every hub (the
 *    arming seam is what records observations, NOT this host);
 *  - the active machine rests at idle (`null`) → [progressionStateFor] yields null → NO progression chrome renders;
 *  - there is no failure region (Compose has no `RemoteFailureView` yet — CYP-823; and nothing false-negative either).
 * So a mounted-but-not-armed shell shows honest UNKNOWN / nothing — NEVER a false trusted / connected / live before
 * the arming seam supplies real, observed data. Display-only, reversible behind the connector seam.
 *
 * ★ Compose reduction vs web-ts. Compose has no `RemoteConnState` *machine* (getState/subscribe) yet — so the
 * connection's machine type is the state SNAPSHOT itself ([RemoteConnState]?, `null` = idle/resting). The host reads
 * it once per switch; **live re-observation (subscribe) is the arming seam** (the source becomes flow-backed there).
 * This is faithful DARK: the idle machine never advances, so a one-shot read is behaviourally identical to
 * subscribe-that-never-fires. The terminal-failure Zone (web-ts `RemoteFailureView`) is deferred to CYP-823.
 */

/**
 * DARK pre-arming connector: opens a resting handle whose machine is `null` (no [RemoteConnState] — idle) and is
 * NEVER driven (no dial, no statusFeed). [progressionStateFor] of a null state ⇒ no progression; and no observation
 * is made ⇒ trust stays UNKNOWN. The real dial connector (socket + statusFeed over the wire) replaces this at arming.
 */
val idleHubConnector: HubConnector<RemoteConnState?> = HubConnector {
    object : HubConnectionHandle<RemoteConnState?> {
        override val machine: RemoteConnState? = null
        override fun close() {}
    }
}

@Composable
fun MultiHubShell(
    /** The known hubs (M1) the switcher lists — from the injected [HubListSource] via the [HubListHolder]. */
    hubListState: HubListState,
    /** Retry a failed `GET /hubs` load. */
    onRetryHubs: () -> Unit,
    /**
     * The active hub at mount. DARK default `""` — Compose has no server-confirmed multi-hub active model yet, so
     * pre-arming NO hub is active (mirrors the AgentShell dark literal). The arming lane supplies the real id.
     */
    initialActiveHubId: String = "",
    /**
     * Observed-trust provenance at mount. DARK default EMPTY → [displayedTrust] is UNKNOWN for every hub. The host
     * does NOT record observations here (that is the arming seam); this is the seam the arming lane pre-seeds — and
     * the seam the tests inject to prove the [displayedTrust] → switcher-badge feed is real (not hardcoded UNKNOWN).
     */
    initialProvenance: TrustProvenance = emptyTrustProvenance,
    /** How to OPEN a live connection. DARK default = the [idleHubConnector]; the real dial is injected at arming. */
    connector: HubConnector<RemoteConnState?> = idleHubConnector,
    modifier: Modifier = Modifier,
) {
    var activeHubId by remember { mutableStateOf(initialActiveHubId) }
    var connState by remember { mutableStateOf<RemoteConnState?>(null) }
    // Pre-arming provenance: fixed at the injected value (empty in prod). The host never records observations here —
    // recording is the arming seam. So displayedTrust is UNKNOWN for every hub until arming supplies real observations.
    val provenance = initialProvenance
    val connection = remember(connector) { createActiveHubConnection(connector) }

    // ONE-ACTIVE: when the active pointer changes, switch the live connection (tear down old, open new) and read the
    // new active machine's state. A blank id ⇒ no active hub ⇒ no connection opened, no state (honest DARK dormant).
    LaunchedEffect(activeHubId) {
        if (activeHubId.isBlank()) {
            connState = null
        } else {
            connection.switchTo(activeHubId)
            connState = connection.active()?.machine // the machine IS the RemoteConnState? snapshot (idle ⇒ null)
        }
    }
    // Tear the connection down on unmount (no leaked background connection).
    DisposableEffect(connection) { onDispose { connection.close() } }

    val progression = connState?.let(::progressionStateFor) // idle/null ⇒ no progression; LOST ⇒ null (terminal)

    Column(modifier) {
        HubSwitcherBar(
            state = hubListState,
            activeHubId = activeHubId,
            // Non-optimistic: set the active pointer; the effect performs the switchTo + re-read. The switcher's
            // active marker follows activeHubId (server-confirmed idiom), never the in-flight click.
            onSwitch = { hubId -> activeHubId = hubId },
            onRetry = onRetryHubs,
            // displayedTrust → the switcher's axis-a badge. Pre-arming (empty provenance) ⇒ UNKNOWN for every hub;
            // an inactive hub that WAS trusted degrades to STALE (never cached-trusted) — the honesty of MC-2.
            trustFor = { hubId -> displayedTrust(provenance, hubId, activeHubId) },
        )
        // Zone-2 in-flight progression — ONLY while the active connect is in an in-flight phase (idle/LOST ⇒ none).
        if (progression != null) {
            ConnectProgressionChrome(state = progression)
        }
    }
}
