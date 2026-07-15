package com.tneff.cyppieagents.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.TranscriptClock
import com.tneff.cyppieagents.agentview.formatLocalHhMm
import com.tneff.cyppieagents.agentview.platformTranscriptClock
import com.tneff.cyppieagents.auth.AuthFormCard
import com.tneff.cyppieagents.auth.AuthTitle
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteFailure
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.remote_connect_authenticating
import kmpcyppieagents.app.shared.generated.resources.remote_connect_auth_rejected
import kmpcyppieagents.app.shared.generated.resources.remote_connect_connected
import kmpcyppieagents.app.shared.generated.resources.remote_connect_e2e_handshake
import kmpcyppieagents.app.shared.generated.resources.a11y_remote_connect_device_not_enrolled
import kmpcyppieagents.app.shared.generated.resources.a11y_remote_connect_device_custody_corrupt
import kmpcyppieagents.app.shared.generated.resources.remote_connect_device_not_enrolled
import kmpcyppieagents.app.shared.generated.resources.remote_connect_device_custody_corrupt
import kmpcyppieagents.app.shared.generated.resources.remote_connect_enroll_codes_unavailable
import kmpcyppieagents.app.shared.generated.resources.remote_connect_handshake_failed
import kmpcyppieagents.app.shared.generated.resources.remote_connect_hub_offline
import kmpcyppieagents.app.shared.generated.resources.remote_connect_relay_dialing
import kmpcyppieagents.app.shared.generated.resources.remote_connect_relay_dropped
import kmpcyppieagents.app.shared.generated.resources.remote_connect_relay_unreachable
import kmpcyppieagents.app.shared.generated.resources.remote_connect_uv_failed
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_changed
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_provisional
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_check
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_hubconnect_presence
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_handshake
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_hub_offline
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_never_online
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_port
import kmpcyppieagents.app.shared.generated.resources.a11y_hubconnect_hub_id
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_empty
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_refresh
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_title
import kmpcyppieagents.app.shared.generated.resources.hubconnect_ready_enter
import kmpcyppieagents.app.shared.generated.resources.hubconnect_presence_offline
import kmpcyppieagents.app.shared.generated.resources.hubconnect_presence_online
import kmpcyppieagents.app.shared.generated.resources.hubconnect_state_attempting
import kmpcyppieagents.app.shared.generated.resources.hubconnect_state_connected
import kmpcyppieagents.app.shared.generated.resources.hubconnect_state_handshake
import kmpcyppieagents.app.shared.generated.resources.load_retry
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-419 (S-L) — Seq B (hub selection) + §6 (local-connect states). **H1 two-truths:** the hub row shows
 * **Registry-Presence** (advisory, dimmed, never success-green, never "connected"), while [ConnectingView] shows
 * **my connection** (the feed's truth) — the two are never mixed. Presence inherits neither green nor the LIVE `●`;
 * the connect states inherit the neutral-in-progress / `●`+`primary`-LIVE idiom from `EventTailPanel`/`AgentWindow`,
 * NOT the `ConnectionBanner` (which is error-toned for CONNECTING).
 */

// --- B2 — hub list ---
@Composable
internal fun HubListView(
    hubs: List<HubDescriptor>,
    viewModel: HubConnectViewModel,
    clock: TranscriptClock = platformTranscriptClock(),
) {
    AuthFormCard(tag = HubConnectTags.HUBS_LIST) {
        AuthTitle(stringResource(Res.string.hubconnect_hubs_title))
        if (hubs.isEmpty()) {
            // Δ2 (CYP-530 UX-QA): register is VESTIGIAL in the list+select model (hubs self-admit to the CP), so the
            // empty state is an HONEST waiting copy (the hub appears once it comes online — the GUI does NOT register
            // it) + a Refresh affordance — NOT a non-functional "register hub" CTA. Copy per UIUX spec @f324aa08.
            Text(
                stringResource(Res.string.hubconnect_hubs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.HUBS_EMPTY),
            )
            Button(
                onClick = viewModel::retryLoadHubs, // re-query the list (check for self-admitted hubs), never register
                modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.HUBS_REFRESH),
            ) { Text(stringResource(Res.string.hubconnect_hubs_refresh)) }
        } else {
            hubs.forEach { hub -> HubRow(hub, clock, onSelect = { viewModel.selectHub(hub) }) }
        }
    }
}

@Composable
private fun HubRow(hub: HubDescriptor, clock: TranscriptClock, onSelect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
            .testTag(HubConnectTags.hubRow(hub.hubId)).padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Δ1 (CYP-530 UX-QA): identity = editable NAME + advisory presence. NO `localhost:port` — that is a LIE for a
        // relay-dialed remote hub (the operator never dials a host:port; a remote hub has no user-facing address).
        Text(hub.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        // Optional short hubId (monospace, dimmed) for identity/disambiguation — an honest key, never a fake address.
        val hubIdShort = hub.hubId.take(8)
        val hubIdA11y = stringResource(Res.string.a11y_hubconnect_hub_id, hubIdShort)
        Text(
            hubIdShort,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.semantics { contentDescription = hubIdA11y },
        )
        PresenceRow(hub, clock)
    }
}

/**
 * Registry-Presence (H1) — advisory only, never a connection guarantee. **Never success-green, never "connected".**
 * Online = a filled NEUTRAL dot (`onSurface`, deliberately not `tertiary` green); offline = a HOLLOW dot
 * (`onSurfaceVariant` ring). Always dot **+ label** + a11y (WCAG 1.4.1 — colour never the sole signal).
 */
@Composable
private fun PresenceRow(hub: HubDescriptor, clock: TranscriptClock) {
    val label = if (hub.online) {
        stringResource(Res.string.hubconnect_presence_online)
    } else {
        stringResource(Res.string.hubconnect_presence_offline, formatLocalHhMm(hub.lastSeen, clock))
    }
    val a11y = stringResource(Res.string.a11y_hubconnect_presence, label)
    Row(
        modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.hubPresence(hub.hubId))
            .semantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hub.online) {
            // Filled neutral dot — NEVER tertiary green (that would overstate presence as a live connection).
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
        } else {
            // Hollow dot (form-distinct from online, not just colour) — dimmed.
            Box(Modifier.size(8.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- B3 — mode choice (CYP-471: Remote now LIVE, routes to the §7 remote connect) ---
@Composable
internal fun ModeView(hub: HubDescriptor, viewModel: HubConnectViewModel) {
    HubCard {
        AuthTitle(hub.name)
        HubConnectModeChooser(
            onConnectLocal = viewModel::connectLocal,
            onConnectRemote = viewModel::connectRemote,
        )
    }
}

// --- §6 — local-connect states (my connection; inherits the neutral/LIVE idiom, never green prematurely) ---
@Composable
internal fun ConnectingView(
    hub: HubDescriptor,
    progress: ConnectProgress,
    viewModel: HubConnectViewModel,
    // CYP-523: forward action from the CONNECTED state into the workspace (the gate's onEnterWorkspace). Only the
    // CONNECTED branch surfaces it → the earlier states can never leak an enter-workspace affordance.
    onEnterWorkspace: () -> Unit = {},
) {
    HubCard {
        when (progress) {
            ConnectProgress.Attempting -> InProgress(
                stringResource(Res.string.hubconnect_state_attempting), HubConnectTags.STATE_ATTEMPTING,
            )
            ConnectProgress.Handshake -> InProgress(
                stringResource(Res.string.hubconnect_state_handshake), HubConnectTags.STATE_HANDSHAKE,
            )
            // CYP-523: CONNECTED is no longer a dead-end — the LIVE indicator + a primary „Loslegen" into the workspace.
            ConnectProgress.Connected -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.STATE_CONNECTED),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // LIVE only — `●`+`primary` (§3.3), reached ONLY on the feed's Connected (never before).
                    Text("● ", color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.hubconnect_state_connected), color = MaterialTheme.colorScheme.onSurface)
                }
                Button(
                    onClick = onEnterWorkspace,
                    modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.STATE_TO_WORKSPACE),
                ) { Text(stringResource(Res.string.hubconnect_ready_enter)) }
            }
            is ConnectProgress.Failed -> {
                val causeText = when (progress.cause) {
                    ConnectCause.HUB_OFFLINE -> stringResource(Res.string.hubconnect_error_hub_offline)
                    ConnectCause.PORT_UNREACHABLE ->
                        stringResource(Res.string.hubconnect_error_port, "localhost:${hub.defaultPort}")
                    ConnectCause.HANDSHAKE_FAILED -> stringResource(Res.string.hubconnect_error_handshake)
                    ConnectCause.NEVER_ONLINE -> stringResource(Res.string.hubconnect_error_never_online)
                }
                // Typed error — the cause comes from the feed, never guessed. errorContainer tone.
                TonedHint(causeText, HintTone.ERROR, HubConnectTags.stateError(progress.cause.tagQualifier))
                Button(onClick = viewModel::connectLocal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.load_retry))
                }
            }
        }
    }
}

/**
 * CYP-482 S-B §9 mount data for the FirstUse OOB-confirm screen at TRUST_CHECK. **Seam-gated:** the real
 * [hubDhPubKey] (CYP-495 `HubDescriptor.dhPubKey`) + [onConfirm]/[onReject] (→ `PendingOobConfirmations`
 * approve/reject over the live feed / trust layer) come from the RR5 live-feed; `null` ⇒ the provisional
 * spinner (INERT, byte-identical to today) until the runway lands.
 */
class OobConfirmMount(
    val hubDhPubKey: ByteArray,
    /**
     * CYP-505 (activation criterion, §5/Q3): `true` for a stub / pre-live mount (the provisional disclosure
     * "vorläufig — echtes Pinnen folgt" stays); **`false` once the live feed does REAL pinning** (`TofuHubTrust`
     * @ approve) ⇒ the disclosure **RETIRES**. Required (no default) so the activation wiring must decide live-vs-
     * stub explicitly — a forgotten default is exactly the over-say this ticket prevents.
     */
    val provisional: Boolean,
    val onConfirm: () -> Unit,
    val onReject: () -> Unit,
)

// --- CYP-471 §7 — remote (Noise-E2E) connect states; renders RemoteSessionState (LIVE only on real CONNECTED).
//     CYP-482 S-B §9: the 3 screen mounts (OOB-confirm @ TRUST_CHECK / PoP @ AUTHENTICATING / revoke @ CONNECTED)
//     ride as OPTIONAL seam params — null (the current callers) ⇒ INERT, byte-identical; the RR5 live-feed
//     provides the real data. Render-at-state is testable now; the real state sources stay seams. ---
@Composable
internal fun RemoteConnectingView(
    hub: HubDescriptor,
    remote: RemoteSessionState,
    viewModel: HubConnectViewModel,
    oobConfirm: OobConfirmMount? = null,
    popPrompt: (@Composable () -> Unit)? = null,
    onEndSession: (() -> Unit)? = null,
    // CYP-523: forward action from CONNECTED into the workspace (the gate's onEnterWorkspace). Surfaced ONLY in the
    // CONNECTED branch → dialing/handshake/trust-check/authenticating can never leak an enter-workspace affordance.
    onEnterWorkspace: () -> Unit = {},
) {
    HubCard {
        when (remote.conn) {
            RemoteConnState.RELAY_DIALING ->
                InProgress(stringResource(Res.string.remote_connect_relay_dialing), RemoteConnectTags.RELAY_DIALING)
            RemoteConnState.E2E_HANDSHAKE ->
                InProgress(stringResource(Res.string.remote_connect_e2e_handshake), RemoteConnectTags.E2E_HANDSHAKE)
            RemoteConnState.TRUST_CHECK ->
                // CYP-482 S-B §9: FirstUse ⇒ the mandatory OOB-confirm screen (real fingerprint). Seam null ⇒ the
                // provisional spinner (INERT) — the trust-check doesn't yet do real pinning until the live feed lands.
                if (oobConfirm != null) {
                    // CYP-505: the live wiring sets provisional=false once pinning is real ⇒ the disclosure retires.
                    OobFingerprintConfirmScreen(
                        hub.name, oobConfirm.hubDhPubKey, oobConfirm.onConfirm, oobConfirm.onReject,
                        provisional = oobConfirm.provisional,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InProgress(stringResource(Res.string.remote_connect_trust_check), RemoteConnectTags.TRUST_CHECK)
                        // CYP-475 §-QA①: honest, USER-VISIBLE provisional disclosure — must not over-say "verified".
                        Text(
                            stringResource(Res.string.remote_connect_trust_provisional),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(RemoteConnectTags.TRUST_PROVISIONAL),
                        )
                    }
                }
            RemoteConnState.AUTHENTICATING ->
                // CYP-482 S-B §4: the PoP prompt inline. Seam null ⇒ the neutral spinner (INERT); the a∧b∧c
                // enforcement (CpJwt ∧ DevicePoP) lands with CYP-459, so this only PRESENTS the prompt for now.
                if (popPrompt != null) popPrompt() else InProgress(stringResource(Res.string.remote_connect_authenticating), RemoteConnectTags.AUTHENTICATING)
            RemoteConnState.RECONNECTING ->
                // H4: ONE neutral relay-drop/reconnect surface (never alarm-red); in-flight honestly uncertain.
                InProgress(stringResource(Res.string.remote_connect_relay_dropped), RemoteConnectTags.RELAY_DROP)
            RemoteConnState.CONNECTED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.CONNECTED),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // LIVE only — `●`+`primary`, reached ONLY on RemoteConnState.CONNECTED (never before).
                    Text("● ", color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.remote_connect_connected), color = MaterialTheme.colorScheme.onSurface)
                }
                // CYP-523: primary forward action → the workspace (no dead-end at „● Verbunden"). Reuses the A4 copy.
                Button(
                    onClick = onEnterWorkspace,
                    modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.TO_WORKSPACE),
                ) { Text(stringResource(Res.string.hubconnect_ready_enter)) }
                // CYP-482 S-B §6: the "end remote session" control (guaranteed local teardown). Seam null ⇒ absent
                // (INERT) — CYP-523 wires it to `viewModel::backToHubList` (end session → back to the hub list).
                onEndSession?.let { RemoteRevokeControl(onEndSession = it) }
            }
            RemoteConnState.LOST -> RemoteFailureView(remote.failure, viewModel)
        }
    }
}

/** §7 failure render: terminal (TrustChanged/AuthRejected) = **no retry** (fail-closed); transport failures = retry. */
@Composable
private fun RemoteFailureView(failure: RemoteFailure?, viewModel: HubConnectViewModel) {
    when (failure) {
        is RemoteFailure.TrustChanged -> Row(
            modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.error("trustChanged")),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // CYP-475 §-QA②: a key change is a verify-OOB ALARM (WARN-amber), NOT "broken" (error-red) — form (▲) +
            // colour + label (WCAG 1.4.1). Terminal / no-retry stays (no retry button here — the fail-closed hard block).
            Text("▲ ", color = severityColor(Severity.WARN))
            Text(
                stringResource(Res.string.remote_connect_trust_changed),
                color = severityColor(Severity.WARN),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RemoteFailure.AuthRejected -> TonedHint(
            stringResource(Res.string.remote_connect_auth_rejected), HintTone.ERROR, RemoteConnectTags.error("authRejected"),
        )
        // CYP-525 (GE4/HB): the DISTINCT "this device isn't set up" truth — actionable, WARN-amber advisory (NOT
        // error-red, it isn't "broken"; NOT terminal like AuthRejected/TrustChanged). The in-flow enroll step is
        // CYP-525 Inc 3; here the fallback surface offers a retry. Own node `error(deviceNotEnrolled)`, never authRejected.
        RemoteFailure.DeviceNotEnrolled -> {
            val a11y = stringResource(Res.string.a11y_remote_connect_device_not_enrolled)
            Column(
                modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.error("deviceNotEnrolled"))
                    .semantics { contentDescription = a11y },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("▲ ", color = severityColor(Severity.WARN))
                    Text(
                        stringResource(Res.string.remote_connect_device_not_enrolled),
                        color = severityColor(Severity.WARN),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = viewModel::connectRemote, modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.RETRY)) {
                    Text(stringResource(Res.string.load_retry))
                }
            }
        }
        RemoteFailure.RelayUnreachable -> RetryableRemoteFailure(
            stringResource(Res.string.remote_connect_relay_unreachable), RemoteConnectTags.error("relayUnreachable"), viewModel,
        )
        RemoteFailure.HubOffline -> RetryableRemoteFailure(
            stringResource(Res.string.remote_connect_hub_offline), RemoteConnectTags.error("hubOffline"), viewModel,
        )
        RemoteFailure.HandshakeFailed -> RetryableRemoteFailure(
            stringResource(Res.string.remote_connect_handshake_failed), RemoteConnectTags.error("handshakeFailed"), viewModel,
        )
        // CYP-525 F3: a local UV failure (wrong PIN / cancelled) — RETRYABLE, never the terminal AuthRejected.
        RemoteFailure.OperatorUvFailed -> RetryableRemoteFailure(
            stringResource(Res.string.remote_connect_uv_failed), RemoteConnectTags.error("operatorUvFailed"), viewModel,
        )
        // CYP-525 Finding ① (GE8): first-enroll codes didn't arrive intact (H3-invalid) — a DELIVERY problem, WARN-amber
        // advisory + RETRY (mirrors DeviceNotEnrolled), NOT the terminal error-red "hub rejected, re-login". Own node
        // error(enrollCodesUnavailable), never authRejected. Frozen key/tag per UIUX spec @550ddb00.
        RemoteFailure.EnrollCodesUnavailable -> Column(
            modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.error("enrollCodesUnavailable")),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("▲ ", color = severityColor(Severity.WARN))
                Text(
                    stringResource(Res.string.remote_connect_enroll_codes_unavailable),
                    color = severityColor(Severity.WARN),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = viewModel::connectRemote, modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.RETRY)) {
                Text(stringResource(Res.string.load_retry))
            }
        }
        // CYP-583: the local device-key custody is present but CORRUPT — a DISTINCT, terminal fail-closed surface
        // (mirrors the server rejectTampered + the vault VaultOpen.Corrupt), NEVER silent and NEVER generic-LOST. No
        // plain retry (it re-fails until the operator RECOVERS via OOB backup code — like TrustChanged, a hard block).
        // WARN-amber (the custody is locally unusable, it is not a hub "reject"). Own node error(deviceCustodyCorrupt).
        // The full dedicated recovery-ack flow is the follow-on (CYP-584); this is the honest distinct signal.
        RemoteFailure.DeviceCustodyCorrupt -> {
            val a11y = stringResource(Res.string.a11y_remote_connect_device_custody_corrupt)
            Row(
                modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.error("deviceCustodyCorrupt"))
                    .semantics { contentDescription = a11y },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▲ ", color = severityColor(Severity.WARN))
                Text(
                    stringResource(Res.string.remote_connect_device_custody_corrupt),
                    color = severityColor(Severity.WARN),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        null -> Unit // clean teardown (Q5 switch) — nothing to render
    }
}

@Composable
private fun RetryableRemoteFailure(text: String, tag: String, viewModel: HubConnectViewModel) {
    TonedHint(text, HintTone.ERROR, tag)
    Button(onClick = viewModel::connectRemote, modifier = Modifier.fillMaxWidth().testTag(RemoteConnectTags.RETRY)) {
        Text(stringResource(Res.string.load_retry))
    }
}

/** In-progress connect state — neutral `onSurfaceVariant`, a neutral spinner, **never** green / a premature LIVE `●`. */
@Composable
private fun InProgress(text: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
