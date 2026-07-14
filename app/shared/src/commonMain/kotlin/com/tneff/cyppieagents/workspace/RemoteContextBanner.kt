package com.tneff.cyppieagents.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_workspace_remote_context
import kmpcyppieagents.app.shared.generated.resources.a11y_workspace_remote_context_tunnel
import kmpcyppieagents.app.shared.generated.resources.remote_connect_trust_pinned
import kmpcyppieagents.app.shared.generated.resources.workspace_remote_context_partial
import kmpcyppieagents.app.shared.generated.resources.workspace_remote_context_tunnel
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-527 (Epic CYP-427 Phase-2 Remote) — the persistent **remote-operating context** banner. ONE node
 * ([WorkspaceTags.REMOTE_CONTEXT]) with two CONNECTED state variants (CYP-427/M2 Seam #1 graduation):
 *
 *  - **B2 — [dataOverTunnel]=false (default, CYP-527):** the WARN-partial form (`workspace_remote_context_partial`),
 *    glyph `▲`, WARN tone — honest that the workspace data-plane does **not** run over the tunnel yet.
 *  - **B3 — [dataOverTunnel]=true (M2 Seam #1):** the affirmative "over an encrypted tunnel" form
 *    (`workspace_remote_context_tunnel`), glyph `●`, **NEUTRAL** (`primary`/`onSurface`) — a state fact, NOT a
 *    success (never `tertiary`/green) and no longer a warning (never WARN-amber). Present-iff a **real** "data over
 *    the tunnel" capability signal (G1); on plain CONNECTED-without-that-signal the caller keeps B2 — no optimistic flip.
 *
 * The **[pinned] sub-node** ([WorkspaceTags.REMOTE_CONTEXT_PINNED], copy `remote_connect_trust_pinned`) renders only
 * in B3 **and** only when identity is REALLY fingerprint-pinned (G2, HC) — provisional trust ⇒ absent, never a faked
 * pin. E2E encryption (the tunnel) and identity pin (TOFU) are two distinct facts; **neither is green** (H3).
 *
 * Tone: the glyph is a **separate** node so colour is never the sole carrier (WCAG 1.4.1). Persistent + not
 * dismissable. Mounted by [RemoteOperatingChrome] iff the remote session is CONNECTED (state-driven).
 */
@Composable
fun RemoteContextBanner(
    hubName: String,
    modifier: Modifier = Modifier,
    dataOverTunnel: Boolean = false,
    pinned: Boolean = false,
) {
    // Tone + copy by state variant. B2 = WARN `▲` (partial); B3 = neutral `●` (affirmative, primary/onSurface).
    val glyph = if (dataOverTunnel) "●" else "▲"
    val glyphColor = if (dataOverTunnel) MaterialTheme.colorScheme.primary else severityColor(Severity.WARN)
    val textColor = if (dataOverTunnel) MaterialTheme.colorScheme.onSurface else severityColor(Severity.WARN)
    val text = stringResource(
        if (dataOverTunnel) Res.string.workspace_remote_context_tunnel else Res.string.workspace_remote_context_partial,
        hubName,
    )
    val a11y = stringResource(
        if (dataOverTunnel) Res.string.a11y_workspace_remote_context_tunnel else Res.string.a11y_workspace_remote_context,
        hubName,
    )
    val pinnedLabel = stringResource(Res.string.remote_connect_trust_pinned)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(WorkspaceTags.REMOTE_CONTEXT)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Polite // a persistent context, not a just-happened event
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The glyph is a SEPARATE node — the form carries the meaning, not colour alone (WCAG 1.4.1).
        Text(glyph, color = glyphColor, style = MaterialTheme.typography.bodyMedium)
        Text(text = text, color = textColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        // G2 / HC — the E2E-pinned indicator: present ONLY in B3 AND only on a real fingerprint pin. Its own
        // (visible) sub-node so QA can tell "pinned" from "banner"; NEVER green (identity pin ≠ success, H3).
        if (dataOverTunnel && pinned) {
            Text(
                pinnedLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(WorkspaceTags.REMOTE_CONTEXT_PINNED),
            )
        }
    }
}
