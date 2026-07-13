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
import kmpcyppieagents.app.shared.generated.resources.workspace_remote_context_partial
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-527 (Epic CYP-427 Phase-2 Remote) — the persistent **remote-operating context** WARN banner. A full-width
 * strip stating, honestly, that the workspace is operating a hub over the **remote (Noise-E2E) transport** — the
 * higher-stakes context the operator should stay aware of. Mounted iff the remote session is CONNECTED (the caller
 * gates it via [AgentShell]'s `remoteContext`, bound to `RemoteSessionState.conn == CONNECTED`, NOT `entered`).
 *
 * **Honesty (per CYP-523's boundary):** this signals the remote **connection context**, not that the workspace's
 * data-plane already runs over the tunnel (the CR3 engine is a separate seam). Tone is **WARN** (`severityColor`,
 * glyph `▲` as a **separate** node so colour is never the sole carrier — WCAG 1.4.1), never `error`-red (nothing is
 * broken) and never `tertiary`-green (remote is not "success"). Persistent + **not dismissable** (unlike the
 * overload banner) — the context holds for the whole remote session. Distinct from [WorkspaceTags.ROLE_INDICATOR]
 * (WHO you are, neutral, always-on): this is WHERE the hub is (WARN, iff remote). Sibling of `OverloadBanner`.
 */
@Composable
fun RemoteContextBanner(hubName: String, modifier: Modifier = Modifier) {
    val warn = severityColor(Severity.WARN)
    // UIUX frozen AC: the visible copy already carries the CR3 disclosure ("data isn't over the tunnel yet"), and
    // the a11y string prefixes "Warnung:" so the ▲ can stay purely decorative (WCAG 1.4.1).
    val text = stringResource(Res.string.workspace_remote_context_partial, hubName)
    val a11y = stringResource(Res.string.a11y_workspace_remote_context, hubName)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(WorkspaceTags.REMOTE_CONTEXT)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                // Polite (not Assertive like the overload reject): a persistent context, not a just-happened event.
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // WARN glyph as a SEPARATE node — the form carries the meaning, not colour alone (WCAG 1.4.1).
        Text("▲", color = warn, style = MaterialTheme.typography.bodyMedium)
        Text(text = text, color = warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
