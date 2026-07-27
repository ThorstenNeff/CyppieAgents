package com.tneff.cyppieagents.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.connect.RemoteRevokeControl
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.net.hub.remote.RemoteConnState
import com.tneff.cyppieagents.net.hub.remote.RemoteSessionState
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_workspace_relay_uncertain
import kmpcyppieagents.app.shared.generated.resources.acl_access_revoked
import kmpcyppieagents.app.shared.generated.resources.remote_connect_relay_dropped
import kmpcyppieagents.app.shared.generated.resources.workspace_relay_uncertain
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-427/M2 (Seams #4/#6/#8) — the **remote-operating chrome region**: ONE top-of-workspace banner region, a
 * transport-/state-driven state machine over [RemoteSessionState] (NOT click-driven). Exactly one line renders per
 * state (G5 exclusivity), so a state transition is a node-swap, never two banners stacked:
 *
 *  - **B1 Local / no session** ([sessionState]==null, [hubName]==null): absent (fail-closed).
 *  - **B2/B3 CONNECTED** (or the CYP-527 fallback: [hubName] present, no live flow): the [RemoteContextBanner]
 *    (WARN-partial vs affirmative-tunnel by [dataOverTunnel], +`.pinned` iff real pin) **plus** the trailing
 *    [RemoteRevokeControl] in the same context row (Seam #8, G6 — revoke present-iff remote-active).
 *  - **B4 RECONNECTING**: the [RemoteRelayDropBanner] (Seam #6) — WARN-amber reconnect banner + the honest
 *    "in-flight uncertain" sub-line iff `inFlightUncertain` (H4). NO revoke (session is not steadily connected).
 *  - **B5 LOST** (terminal): absent here — the session-ended path is a separate, existing flow (out-of-scope).
 *
 * [dataOverTunnel]/[pinned] are the M2 capability seams: today no real "data over the tunnel" signal exists
 * (`RemoteHubTransport` = fail-loud stub) ⇒ both default false ⇒ B2 stays (G1: no optimistic flip; correct, not a
 * bug). When the real transport/CR3 capability + `HubTrust` pin land (RR5, G7), the composition root feeds them true
 * → B3/`.pinned` with these same tags/copy/nodes unchanged. [ttl] is the Seam-#8 operator-session-TTL (seam-gated,
 * null until CYP-459).
 */
@Composable
fun RemoteOperatingChrome(
    hubName: String?,
    sessionState: StateFlow<RemoteSessionState>?,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
    dataOverTunnel: Boolean = false,
    pinned: Boolean = false,
    ttl: String? = null,
    // CYP-819 (A1): a session-wide 1008 auth-revoke (fanned in from the four status feeds) → the covering,
    // terminal ERROR-red banner that SUPERSEDES every transient session banner. Absent by default (byte-identical).
    accessRevoked: Boolean = false,
) {
    // CYP-819 (A1): a terminal revoke covers the whole workspace and outranks CONNECTED/RECONNECTING — one banner
    // (shared bearer), static, ERROR-red. Checked FIRST so it supersedes the transient session-state banners below.
    if (accessRevoked) {
        AccessRevokedBanner(modifier)
        return
    }
    if (sessionState == null) {
        // No live session flow (CYP-527 fallback): the context banner is present-iff a hub name is provided (the
        // caller's CONNECTED gate). No revoke without an active-session flow to end.
        if (hubName != null) {
            RemoteContextBanner(hubName = hubName, modifier = modifier, dataOverTunnel = dataOverTunnel, pinned = pinned)
        }
        return
    }
    val state by sessionState.collectAsState()
    when (state.conn) {
        RemoteConnState.RECONNECTING -> RemoteRelayDropBanner(inFlightUncertain = state.inFlightUncertain, modifier = modifier)
        RemoteConnState.CONNECTED ->
            if (hubName != null) {
                Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RemoteContextBanner(
                        hubName = hubName,
                        modifier = Modifier.weight(1f),
                        dataOverTunnel = dataOverTunnel,
                        pinned = pinned,
                    )
                    // Seam #8 / G6 — the "end remote session" control rides trailing in the context row (present-iff
                    // remote CONNECTED). Confirm → onEndSession() = guaranteed local teardown (backToHubList/close).
                    RemoteRevokeControl(onEndSession = onEndSession, ttl = ttl)
                }
            }
        // LOST (terminal, out-of-scope) / pre-connect states (handled by the connect flow) → absent.
        else -> {}
    }
}

/**
 * CYP-819 (A1) — the ONE covering, session-wide **access-revoked** banner: a terminal 1008 (VIOLATED_POLICY)
 * auth-revoke on the shared bearer hits every agent window at once, so a single full-width banner covers them all
 * (never N per-window chips). **ERROR-red `✕`, STATIC** — a revoke is terminal ("neu anmelden"), not a reconnect
 * (contrast [RemoteRelayDropBanner]'s WARN-amber `▲` + spinner semantics). Copy reuses `acl_access_revoked` (the
 * shared revoked anchor); the `✕` is a SEPARATE node (form carries meaning, WCAG 1.4.1); a11y **Assertive** (an
 * unsolicited terminal result the operator MUST hear).
 */
@Composable
fun AccessRevokedBanner(modifier: Modifier = Modifier) {
    val revokedText = stringResource(Res.string.acl_access_revoked)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .testTag(WorkspaceTags.ACCESS_REVOKED)
            .semantics(mergeDescendants = true) {
                contentDescription = revokedText
                liveRegion = LiveRegionMode.Assertive
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✕", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        Text(
            revokedText,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * CYP-427/M2 (Seam #6) — the in-operation relay-drop surface (B4). ONE global reconnect banner (never N per-window
 * chips) with an honest in-flight-uncertain sub-line. **WARN-amber `▲`, never red** — the session is reconnecting,
 * not dead (terminal loss = `LOST` = a separate path). [inFlightUncertain] gates the H4 sub-node: in-flight work is
 * explicitly UNCONFIRMED, never silently shown as done.
 */
@Composable
fun RemoteRelayDropBanner(inFlightUncertain: Boolean, modifier: Modifier = Modifier) {
    val warn = severityColor(Severity.WARN)
    val dropText = stringResource(Res.string.remote_connect_relay_dropped)
    val uncertainText = stringResource(Res.string.workspace_relay_uncertain)
    val uncertainA11y = stringResource(Res.string.a11y_workspace_relay_uncertain)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Reconnect banner — WARN-amber ▲ (never red). Glyph a separate node (WCAG 1.4.1).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WorkspaceTags.RELAY_DROP)
                .semantics(mergeDescendants = true) {
                    contentDescription = dropText
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▲", color = warn, style = MaterialTheme.typography.bodyMedium)
            Text(dropText, color = warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        // H4 / G4 — in-flight actions honestly uncertain, present-iff inFlightUncertain. Never silently "done".
        if (inFlightUncertain) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WorkspaceTags.RELAY_DROP_UNCERTAIN)
                    .semantics(mergeDescendants = true) {
                        contentDescription = uncertainA11y
                        liveRegion = LiveRegionMode.Polite
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▲", color = warn, style = MaterialTheme.typography.bodyMedium)
                Text(uncertainText, color = warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}
