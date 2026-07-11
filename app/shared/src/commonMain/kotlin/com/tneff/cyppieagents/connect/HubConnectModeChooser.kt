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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_hubconnect_mode_remote_disabled
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_connect
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_local
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_local_sub
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_remote
import kmpcyppieagents.app.shared.generated.resources.hubconnect_mode_remote_soon
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
    modifier: Modifier = Modifier,
) {
    val remoteDisabledA11y = stringResource(Res.string.a11y_hubconnect_mode_remote_disabled)
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                // Local is the ONLY actionable option and the default selection — Remote is never preselected.
                selected = true,
                onClick = {}, // already selected; the explicit action is the Connect button below.
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = true,
                modifier = Modifier.testTag(HubConnectTags.MODE_LOCAL),
            ) { Text(stringResource(Res.string.hubconnect_mode_local), maxLines = 1) }
            SegmentedButton(
                selected = false, // H2: NEVER preselected on Remote.
                onClick = {}, // unreachable — the segment is disabled (no fake click).
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = false, // honestly disabled ("kommt bald"), not clickable.
                modifier = Modifier
                    .testTag(HubConnectTags.MODE_REMOTE)
                    .semantics { contentDescription = remoteDisabledA11y },
            ) { Text(stringResource(Res.string.hubconnect_mode_remote), maxLines = 1) }
        }
        // Local sub — describes the active option (private + fast, same network).
        Text(
            text = stringResource(Res.string.hubconnect_mode_local_sub),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Remote "kommt bald" — the honest disabled explanation (GATED idiom; form + label + a11y, never colour-alone).
        TonedHint(
            text = stringResource(Res.string.hubconnect_mode_remote_soon),
            tone = HintTone.GATED,
            tag = HubConnectTags.MODE_REMOTE_SOON,
        )
        // Primary action — only Local is actionable in Phase 1 (§6 local-connect flow lands in S-L).
        Button(
            onClick = onConnectLocal,
            modifier = Modifier.testTag(HubConnectTags.MODE_CONNECT),
        ) { Text(stringResource(Res.string.hubconnect_mode_connect)) }
    }
}
