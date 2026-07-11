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
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.agentview.TranscriptClock
import com.tneff.cyppieagents.agentview.formatLocalHhMm
import com.tneff.cyppieagents.agentview.platformTranscriptClock
import com.tneff.cyppieagents.auth.AuthFormCard
import com.tneff.cyppieagents.auth.AuthTitle
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_hubconnect_presence
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_handshake
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_hub_offline
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_never_online
import kmpcyppieagents.app.shared.generated.resources.hubconnect_error_port
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_empty
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_register
import kmpcyppieagents.app.shared.generated.resources.hubconnect_hubs_title
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
            Text(
                stringResource(Res.string.hubconnect_hubs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.HUBS_EMPTY),
            )
            Button(
                onClick = viewModel::registerNewHub,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.hubconnect_hubs_register)) }
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
        // Display the editable NAME (never the opaque id, which is only the tag segment).
        Text(hub.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "localhost:${hub.defaultPort}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

// --- B3 — mode choice (wire the CYP-416 chooser; Remote stays honestly disabled there) ---
@Composable
internal fun ModeView(hub: HubDescriptor, viewModel: HubConnectViewModel) {
    HubCard {
        AuthTitle(hub.name)
        HubConnectModeChooser(onConnectLocal = viewModel::connectLocal)
    }
}

// --- §6 — local-connect states (my connection; inherits the neutral/LIVE idiom, never green prematurely) ---
@Composable
internal fun ConnectingView(hub: HubDescriptor, progress: ConnectProgress, viewModel: HubConnectViewModel) {
    HubCard {
        when (progress) {
            ConnectProgress.Attempting -> InProgress(
                stringResource(Res.string.hubconnect_state_attempting), HubConnectTags.STATE_ATTEMPTING,
            )
            ConnectProgress.Handshake -> InProgress(
                stringResource(Res.string.hubconnect_state_handshake), HubConnectTags.STATE_HANDSHAKE,
            )
            ConnectProgress.Connected -> Row(
                modifier = Modifier.fillMaxWidth().testTag(HubConnectTags.STATE_CONNECTED),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // LIVE only — `●`+`primary` (§3.3), reached ONLY on the feed's Connected (never before).
                Text("● ", color = MaterialTheme.colorScheme.primary)
                Text(stringResource(Res.string.hubconnect_state_connected), color = MaterialTheme.colorScheme.onSurface)
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
