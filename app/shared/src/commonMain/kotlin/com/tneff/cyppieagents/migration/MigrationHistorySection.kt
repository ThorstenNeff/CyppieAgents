package com.tneff.cyppieagents.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.eventlog.severityColor
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-220 §8 — the migration history, and its **two DISTINCT empty states**. The difference is the point (and it
 * must come from the server, not be inferred): [HistoryAvailability.RECORDING] + no entries ⇒ "no migration yet"
 * (neutral); [HistoryAvailability.UNAVAILABLE] ⇒ "history is not being recorded" (WARN) — the honest surfacing of
 * the silent `MigrationAudit.NONE` default (spec §8, the same safe-but-silent class as the operator-pin). A test
 * that only checks "list empty" cannot tell the two apart — so they carry different tags AND different tones.
 * Without the BE-1 flag the honest choice is to not build §8; here the flag is the [MigrationHistory.availability].
 */
@Composable
fun MigrationHistorySection(history: MigrationHistory, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(MigrationTags.HISTORY),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.migration_history_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )

        when {
            history.availability == HistoryAvailability.UNAVAILABLE -> {
                // The sink is NOT durably wired → "not recorded". WARN (severityColor + a SEPARATE ▲, WCAG 1.4.1).
                // NEVER an empty list, which would silently read as "nothing happened".
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(MigrationTags.HISTORY_UNAVAILABLE),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("▲", color = severityColor(Severity.WARN), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(Res.string.migration_history_unavailable),
                        color = severityColor(Severity.WARN),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            history.entries.isEmpty() -> {
                // The sink IS wired, nothing has happened yet — a neutral, honest "no migration yet".
                Text(
                    text = stringResource(Res.string.migration_history_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().testTag(MigrationTags.HISTORY_EMPTY),
                )
            }
            else -> history.entries.forEachIndexed { i, entry -> HistoryRow(i, entry) }
        }
    }
}

/** A history row (secret-free fields). EventRow-style reuse is a follow-up — the audit model differs from Event. */
@Composable
private fun HistoryRow(index: Int, entry: MigrationHistoryEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(MigrationTags.historyRow(index)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${entry.storeKey} · ${entry.phase}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.result,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
