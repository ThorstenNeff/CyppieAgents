package com.tneff.cyppieagents.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.severityContainer
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_hubcap_overload
import kmpcyppieagents.app.shared.generated.resources.hubcap_overload_dismiss
import kmpcyppieagents.app.shared.generated.resources.hubcap_overload_title
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-417 (Epic CYP-395 S-G) — the hub-scoped **overload-reject banner** (the `FrameBanner` idiom lifted to a
 * workspace-scoped composable, S-3). A full-width **WARN-amber** strip (`severityContainer(WARN)`, glyph `▲`) —
 * **never** `tertiary`-green (protection is not success) and **never** `error`-red (nothing crashed): a fail-closed
 * reject is the machine limit reached (H3). **Honesty split (H2):** the copy states the **fact** ("Spawn abgelehnt")
 * and the **estimated reason** ("würde überlasten") separately.
 *
 * Rendered ONLY on a real server-side reject (H5 — the client never invents it from the estimate). Persistent while
 * the overload holds and **dismissable** (Q5, driven by [CapacityViewModel]); the durable record is the content-free
 * WARN event in the log, not this live strip. a11y is **assertive** — the user just tried to add an agent, so the
 * honest rejection must announce at once.
 */
@Composable
fun OverloadBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val (container, content) = severityContainer(Severity.WARN)
    val a11y = stringResource(Res.string.a11y_hubcap_overload)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(WorkspaceTags.OVERLOAD_BANNER)
            .semantics(mergeDescendants = true) {
                contentDescription = a11y
                liveRegion = LiveRegionMode.Assertive
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("▲", color = content, style = MaterialTheme.typography.bodyMedium) // WARN glyph (not colour-alone, 1.4.1)
        Text(
            text = stringResource(Res.string.hubcap_overload_title),
            color = content,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(WorkspaceTags.OVERLOAD_BANNER_DISMISS),
        ) { Text(stringResource(Res.string.hubcap_overload_dismiss)) }
    }
}
