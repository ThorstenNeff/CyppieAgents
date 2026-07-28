package com.tneff.cyppieagents.multihub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.connect.RemoteConnectTags
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.hubconnect_ready_enter
import kmpcyppieagents.app.shared.generated.resources.remote_connect_authenticating
import kmpcyppieagents.app.shared.generated.resources.remote_connect_connected
import kmpcyppieagents.app.shared.generated.resources.remote_connect_e2e_handshake
import kmpcyppieagents.app.shared.generated.resources.remote_connect_relay_dialing
import kmpcyppieagents.app.shared.generated.resources.remote_connect_relay_dropped
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_check
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_provisional
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-866 (Compose-M5-Mirror of web-ts CYP-855) — the **connect-progression chrome**: the Zone-2 in-flight step of
 * the ACTIVE hub's connect, mounted by the switcher-host (M4) from [progressionStateFor]. Copy/tags reuse the
 * existing CYP-827 `remote_connect_*` strings + [RemoteConnectTags] (no new copy). It is NEVER always-visible Zone-1
 * status — a transient step must not persist as durable status; a terminal/ended state resolves to `null` at
 * [progressionStateFor] (the Failure-Region CYP-823 owns those) and this chrome is simply not mounted.
 *
 * Honesty core (CYP-827 §2, mirrored):
 *  - **trust-check is PROVISIONAL** — an always-visible "vorläufig, echtes Pinnen folgt" disclosure; it must NEVER
 *    read as "verified/trusted" before real pinning (that verdict is axis-a `HubTrustState`, Zone-1, separate).
 *  - **CONNECTED** shows the `●` LIVE marker — reached ONLY at real connected, never optimistically; and this liveness
 *    is NOT the axis-a trust affirmation (two axes, never conflated).
 *  - **a11y:** every step is `liveRegion = Polite` (role=status/aria-live=polite, calm). Assertive stays reserved for
 *    terminal failure / active-hub-switch / revoke — a progression that shouted every step would drown the terminal.
 */
@Composable
fun ConnectProgressionChrome(
    state: ProgressionState,
    modifier: Modifier = Modifier,
    /** CONNECTED-only forward action into the workspace (no dead-end, CYP-523 parity). */
    onEnterWorkspace: (() -> Unit)? = null,
) {
    val (textKey: StringResource, tag: String) = when (state) {
        ProgressionState.DIALING -> Res.string.remote_connect_relay_dialing to RemoteConnectTags.RELAY_DIALING
        ProgressionState.HANDSHAKE -> Res.string.remote_connect_e2e_handshake to RemoteConnectTags.E2E_HANDSHAKE
        ProgressionState.TRUST_CHECK -> Res.string.remote_connect_trust_check to RemoteConnectTags.TRUST_CHECK
        ProgressionState.AUTHENTICATING -> Res.string.remote_connect_authenticating to RemoteConnectTags.AUTHENTICATING
        ProgressionState.RECONNECTING -> Res.string.remote_connect_relay_dropped to RemoteConnectTags.RELAY_DROP
        ProgressionState.CONNECTED -> Res.string.remote_connect_connected to RemoteConnectTags.CONNECTED
    }
    val connected = state == ProgressionState.CONNECTED

    Column(
        // Zone-2 in-flight card: role=status / aria-live=polite for EVERY step (never Assertive).
        modifier = modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // `●` LIVE idiom — ONLY at real CONNECTED, never optimistically before it. Liveness, NOT axis-a trust.
            if (connected) {
                Text("●", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(textKey), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        if (state == ProgressionState.TRUST_CHECK) {
            // PROVISIONAL — in-flight, never "verified". Neutral labelSmall, no alarm.
            Text(
                stringResource(Res.string.remote_connect_trust_provisional),
                modifier = Modifier.testTag(RemoteConnectTags.TRUST_PROVISIONAL),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (connected && onEnterWorkspace != null) {
            Button(
                onClick = onEnterWorkspace,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp).testTag(RemoteConnectTags.TO_WORKSPACE),
            ) { Text(stringResource(Res.string.hubconnect_ready_enter)) }
        }
    }
}
