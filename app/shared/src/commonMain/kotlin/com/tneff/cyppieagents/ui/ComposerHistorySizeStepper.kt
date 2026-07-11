package com.tneff.cyppieagents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.composer_history_size_help
import kmpcyppieagents.app.shared.generated.resources.composer_history_size_label
import org.jetbrains.compose.resources.stringResource

/**
 * `testTag` contract for the composer input-history size control (CYP-387). Prefixless `<area>.<element>` per
 * the tags convention; area `composerHistory`. **Shared API with QA — do not rename silently; coordinate via PO.**
 */
object ComposerHistoryTags {
    const val STEPPER = "composerHistory.stepper"
    const val DEC = "composerHistory.dec"
    const val VALUE = "composerHistory.value"
    const val INC = "composerHistory.inc"
}

/**
 * CYP-387 §3.1 — the personal, **ungated** stepper for the ONE global input-history size N (arrow-up/down recall):
 * `[−] N [+]`, range [MIN_COMPOSER_HISTORY_SIZE]..[MAX_COMPOSER_HISTORY_SIZE] (0 = off). Rides the always-visible
 * [com.tneff.cyppieagents.project.ProjectSwitcherBar] trailing slot beside the theme toggle — same family, same
 * "not operator-gated, not project-scoped" placement. It only *drives* N via [onChange]; the `App.kt` seam owns
 * the clamp + durable persistence.
 *
 * The stepper never emits an out-of-range value (the − / + are disabled at the bounds), and the `App.kt` seam
 * clamps as a backstop. a11y: the row merges to one node named "<label>: <N>" with the help as its state hint;
 * `0` disabling recall is an honest, screen-reader-announced value, not a dead control.
 */
@Composable
fun ComposerHistorySizeStepper(
    size: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // On a narrow bar the leading label drops (the a11y name still carries it), keeping just `[−] N [+]`.
    compact: Boolean = false,
) {
    val label = stringResource(Res.string.composer_history_size_label)
    val help = stringResource(Res.string.composer_history_size_help)
    Row(
        modifier = modifier
            .testTag(ComposerHistoryTags.STEPPER)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $size"
                stateDescription = help
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!compact) {
            Text(text = "$label:", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
        }
        TextButton(
            onClick = { onChange(size - 1) },
            enabled = size > MIN_COMPOSER_HISTORY_SIZE,
            modifier = Modifier.testTag(ComposerHistoryTags.DEC),
        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(
            text = size.toString(),
            modifier = Modifier.testTag(ComposerHistoryTags.VALUE),
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(
            onClick = { onChange(size + 1) },
            enabled = size < MAX_COMPOSER_HISTORY_SIZE,
            modifier = Modifier.testTag(ComposerHistoryTags.INC),
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}
