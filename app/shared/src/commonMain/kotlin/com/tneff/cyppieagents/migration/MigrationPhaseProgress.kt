package com.tneff.cyppieagents.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-220 §5 — the phase-progress renderer, and the resolution of the deferred glyph-node
 * (`FirstRunSteps.kt:111-112`): **"done" carries NO glyph — its proof value replaces it** (H4). COPY shows
 * "2.341 Zeilen", VERIFY "2.341 / 2.341 · Prüfsumme identisch", REBIND the target DSN label. Three wins: no new
 * (tofu-prone, CYP-54) glyph; no green (success is never green here — it is a number); the proof replaces the
 * symbol. **`null` ≠ "0"** — a phase with no backend value stays value-LESS (label + marker), never a fabricated 0.
 * Only the verified glyphs `·` (pending) / `✕` (failed) are used; running is a spinner (no glyph); "done" is bare.
 */
@Composable
fun MigrationPhaseProgress(run: MigrationRun, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(MigrationTags.PHASES),
        // The phase container is a Polite live region (spec §9); a failed row escalates to Assertive on its own row.
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (p in run.phases) PhaseRow(p)
        if (run.slow) {
            // §5.3 — a calm "still running" life-sign after the slow threshold. NO timeout, NO abort offer (same
            // semantics as the CYP-629 clone slow-line). GATED tone: informational, never an alarm.
            TonedHint(text = stringResource(Res.string.migration_slow), tone = HintTone.GATED, tag = MigrationTags.SLOW)
        }
    }
}

@Composable
private fun PhaseRow(progress: PhaseProgress) {
    val seg = progress.phase.name.lowercase()                 // window / copy / verify / rebind
    val qualifier = progress.state.name.lowercase()           // pending / running / done / failed / skipped
    val label = stringResource(phaseLabelKey(progress.phase))
    val stateWord = phaseStateWord(progress.state)
    val dimmed = progress.state == PhaseState.SKIPPED || progress.state == PhaseState.PENDING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${MigrationTags.phase(seg)}.$qualifier")
            // The state for SR must be EXPLICIT (spec §9) — "done" carries no glyph, so it must not be inferred
            // from a marker. Skipped ("not reached") has no state word: its absence is the truth (dimmed label).
            .then(if (stateWord != null) Modifier.semantics { stateDescription = stateWord } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseMarker(progress.state, label)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // The proof value — the thing that REPLACES the "done" glyph (H4). `null` ⇒ not rendered (never "0").
        val value = progress.value
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(MigrationTags.phaseValue(seg)),
            )
        }
    }
}

@Composable
private fun PhaseMarker(state: PhaseState, label: String) {
    when (state) {
        // `·` = the verified GATED glyph (hintGlyph(GATED)); pending is neutral.
        PhaseState.PENDING -> Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        // Spinner (no glyph) + a contentDescription so the SR-mute spinner is never the sole running signal (§9).
        PhaseState.RUNNING -> {
            val running = stringResource(Res.string.a11y_migration_phase_running, label)
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = running },
                strokeWidth = 2.dp,
            )
        }
        // ⭐ "done" carries NO glyph — the proof value in the row is the marker (H4, spec §5.2). Zero-width marker.
        PhaseState.DONE -> Unit
        // `✕` = the verified ERROR glyph (hintGlyph(ERROR)).
        PhaseState.FAILED -> Text("✕", color = severityColor(Severity.ERROR), style = MaterialTheme.typography.bodySmall)
        // "not reached": no marker, dimmed label (handled by the caller) — absence is the truth here.
        PhaseState.SKIPPED -> Unit
    }
}

private fun phaseLabelKey(phase: MigrationPhase) = when (phase) {
    MigrationPhase.WINDOW -> Res.string.migration_phase_window
    MigrationPhase.COPY -> Res.string.migration_phase_copy
    MigrationPhase.VERIFY -> Res.string.migration_phase_verify
    MigrationPhase.REBIND -> Res.string.migration_phase_rebind
}

/** The explicit SR state word (spec §9). `null` for SKIPPED — "not reached" has no state to announce. */
@Composable
private fun phaseStateWord(state: PhaseState): String? = when (state) {
    PhaseState.PENDING -> stringResource(Res.string.a11y_migration_state_pending)
    PhaseState.RUNNING -> stringResource(Res.string.a11y_migration_state_running)
    PhaseState.DONE -> stringResource(Res.string.a11y_migration_state_done)
    PhaseState.FAILED -> stringResource(Res.string.a11y_migration_state_failed)
    PhaseState.SKIPPED -> null
}
