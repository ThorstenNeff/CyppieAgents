package com.tneff.cyppieagents.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.tneff.cyppieagents.eventlog.severityContainer
import com.tneff.cyppieagents.model.Severity
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_hubcap_readout
import kmpcyppieagents.app.shared.generated.resources.a11y_hubcap_readout_nomax
import kmpcyppieagents.app.shared.generated.resources.hubcap_full
import kmpcyppieagents.app.shared.generated.resources.hubcap_readout
import kmpcyppieagents.app.shared.generated.resources.hubcap_readout_nomax
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-417 (Epic CYP-395 S-G) — the hub-capacity readout, a **neutral `Pill`** (the [WindowBadge] idiom) in the
 * workspace top bar. Three honest states (H4 gradient; both counts are **server-authoritative**, never client-derived):
 *  - **no data** ([capacity] `null`, e.g. pre-`GET /api/capacity`) → **absent** (H1/Q3 `null≠0`, no "0/0");
 *  - **max not yet estimated** ([HubCapacity.estimatedMax] `null`, PO A2 decision) → **"N aktiv"** without a max,
 *    **neutral** (`current` is known + authoritative; the estimate is still pending);
 *  - **known max, headroom** (`current < estimatedMax`) → **neutral** "N/M" (`primaryContainer`, NOT green/amber);
 *  - **full** (`current == estimatedMax`, Q2) → **WARN-amber** "N/M" + a "voll" marker (`.full`).
 *
 * The `/M` is an **estimate**, not a hard SLA (H1) — the a11y copy says "(estimated)". Colour is never the sole
 * signal (1.4.1): the count + label carry it, the pill node carries the a11y description.
 */
@Composable
fun CapacityReadout(capacity: HubCapacity?, modifier: Modifier = Modifier) {
    // No capacity data at all (pre-GET / unauthenticated) ⇒ show NOTHING (H1/Q3) — never "0/0" or a placeholder.
    val cap = capacity ?: return
    val max = cap.estimatedMax
    val full = cap.isFull // false whenever max is null

    // WARN-amber only when full; neutral for headroom AND for the no-max "N aktiv" state (H4 — never green/amber-early).
    val (container, content) = if (full) {
        severityContainer(Severity.WARN)
    } else {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }
    val (readout, a11y) = if (max == null) {
        stringResource(Res.string.hubcap_readout_nomax, cap.current.toString()) to
            stringResource(Res.string.a11y_hubcap_readout_nomax, cap.current.toString())
    } else {
        stringResource(Res.string.hubcap_readout, cap.current.toString(), max.toString()) to
            stringResource(Res.string.a11y_hubcap_readout, cap.current.toString(), max.toString())
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(WorkspaceTags.CAPACITY) // present ⇔ capacity data exists
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(readout, color = content, style = MaterialTheme.typography.labelSmall)
        if (full) {
            // The full marker (Q2) — carries the `.full` qualifier tag; the amber container already tones the pill.
            Text(
                stringResource(Res.string.hubcap_full),
                color = content,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.testTag(WorkspaceTags.CAPACITY_FULL),
            )
        }
    }
}
