package com.tneff.cyppieagents.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_connect
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_local
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_local_sub
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_remote
import kmpcyppieagents.app.shared.generated.resources.remote_connect_mode_sub
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-416 (Epic CYP-395 S-M) — the hubConnect §B3 **mode-chooser**: **Lokal** (active, the default) | **Remote**
 * (visibly DISABLED — "kommt bald", H2). Phase 1 is loopback-only (R5); Remote (Noise-E2E via the Control Plane)
 * is Phase 2, so it is **honestly non-interactive** here — not preselected, not clickable, no fake click that then
 * fails. The [com.tneff.cyppieagents.net.hub.RemoteHubTransport] fail-loud stub (S-H / CYP-411) is the backstop if
 * any code path ever reaches Remote.
 *
 * **Standalone** per the ratified S-M/S-L split: this is the component; S-L wires it into the connect flow (§6
 * states + the hub list) and drives [onConnectLocal] into the real local connect. **Colour is never the sole
 * signal (WCAG 1.4.1):** the disabled Remote carries a form (disabled segment) + label + a11y, and the "kommt bald"
 * hint reuses the [TonedHint] GATED idiom (a "·" glyph + `onSurfaceVariant`, the message carrying the meaning).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubConnectModeChooser(
    onConnectLocal: () -> Unit,
    onConnectRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // CYP-471: Remote is now LIVE (Phase 2). Neither mode is preselected onto the other's surface — the user
    // chooses, and the Connect button routes by the selection (no fake click, no mode confusion).
    var remoteSelected by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !remoteSelected,
                onClick = { remoteSelected = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = true,
                modifier = Modifier.testTag(HubConnectTags.MODE_LOCAL),
            ) { Text(stringResource(Res.string.hubconnect_mode_local), maxLines = 1) }
            SegmentedButton(
                selected = remoteSelected,
                onClick = { remoteSelected = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = true, // CYP-471: Remote (Noise-E2E via the Control Plane) is now selectable.
                modifier = Modifier.testTag(HubConnectTags.MODE_REMOTE),
            ) { Text(stringResource(Res.string.hubconnect_mode_remote), maxLines = 1) }
        }
        // Sub for the selected mode — Local = private+fast same-network; Remote = Noise-E2E via the Control Plane.
        Text(
            text = stringResource(if (remoteSelected) Res.string.remote_connect_mode_sub else Res.string.hubconnect_mode_local_sub),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Primary action — routes to the SELECTED mode's connect (Local §6 / Remote §7).
        Button(
            onClick = { if (remoteSelected) onConnectRemote() else onConnectLocal() },
            modifier = Modifier.testTag(HubConnectTags.MODE_CONNECT),
        ) { Text(stringResource(Res.string.hubconnect_mode_connect)) }
    }
}
