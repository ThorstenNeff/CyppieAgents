package com.tneff.cyppieagents.compact

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.eventlog.EventRow
import com.tneff.cyppieagents.eventlog.formatLocalHhMmSsMillis
import com.tneff.cyppieagents.eventlog.severityColor
import com.tneff.cyppieagents.model.CompactConfig
import com.tneff.cyppieagents.model.CompactStatus
import com.tneff.cyppieagents.model.Event
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import com.tneff.cyppieagents.window.formatCompactTokens
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_compact_allow
import kmpcyppieagents.app.shared.generated.resources.compact_allow_hint
import kmpcyppieagents.app.shared.generated.resources.compact_events_empty
import kmpcyppieagents.app.shared.generated.resources.compact_run_current
import kmpcyppieagents.app.shared.generated.resources.compact_run_last
import kmpcyppieagents.app.shared.generated.resources.compact_allow_label
import kmpcyppieagents.app.shared.generated.resources.compact_last_run_aborted
import kmpcyppieagents.app.shared.generated.resources.compact_last_run_ok
import kmpcyppieagents.app.shared.generated.resources.compact_last_run_timeout
import kmpcyppieagents.app.shared.generated.resources.compact_status_idle
import kmpcyppieagents.app.shared.generated.resources.compact_status_label
import kmpcyppieagents.app.shared.generated.resources.compact_status_off
import kmpcyppieagents.app.shared.generated.resources.compact_status_running
import kmpcyppieagents.app.shared.generated.resources.a11y_compact_threshold_input
import kmpcyppieagents.app.shared.generated.resources.a11y_compact_stagger_input
import kmpcyppieagents.app.shared.generated.resources.a11y_compact_round_gap_input
import kmpcyppieagents.app.shared.generated.resources.compact_stagger_label
import kmpcyppieagents.app.shared.generated.resources.compact_round_gap_label
import kmpcyppieagents.app.shared.generated.resources.compact_timing_range_error
import kmpcyppieagents.app.shared.generated.resources.compact_timing_set_confirm
import kmpcyppieagents.app.shared.generated.resources.compact_threshold_label
import kmpcyppieagents.app.shared.generated.resources.compact_threshold_range_error
import kmpcyppieagents.app.shared.generated.resources.compact_threshold_set
import kmpcyppieagents.app.shared.generated.resources.compact_threshold_set_confirm
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-326 — the compact-orchestration window body (UIUX spec §1.3). One column, top-down:
 * the global "compact allowed" control (operator checkbox / member read-only chip), the mandatory INFO
 * disclosure hint, the operator gate hint (members), then the **server-mirror** facts (threshold, status,
 * last run X/N).
 *
 * Honesty (§3): default OFF + operator-gated + never optimistic; `compact.completed`/a full run are
 * **neutral, never green**; a **timeout is WARN amber**, never faked as done; the server state is a mirror —
 * when UNKNOWN the status/threshold rows are **absent**, never a defaulted "idle"/"off".
 */
@Composable
fun CompactPanel(
    viewModel: CompactViewModel,
    modifier: Modifier = Modifier,
    /** CYP-327 Feature B: ALL compact events (the 4 orchestration types) from the operator feed; `null` → no event
     *  section (non-operator). The panel scopes them to the current/last run via `status.lastRun.correlationId`. */
    compactEvents: List<Event>? = null,
) {
    val state by viewModel.state.collectAsState()
    val status = state.status
    val allowed = status?.allowed ?: false // fail-closed default when the server state is unknown

    // CYP-328: while this window is open, re-poll the server-owned status so a sequence that STARTS mid-open goes
    // live (running / last-run) without any config-change. Keyed on the VM; leaving composition cancels the poll.
    LaunchedEffect(viewModel) { viewModel.observeWhileOpen() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(CompactTags.PANEL),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // §1.2 Control: operator → live checkbox; non-operator → read-only chip (ACL honesty: a form marker,
        // never a fake/disabled switch that implies editability).
        val allowLabel = stringResource(Res.string.compact_allow_label)
        if (state.editable) {
            val allowA11y = stringResource(Res.string.a11y_compact_allow)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(
                    checked = allowed,
                    onCheckedChange = { viewModel.setAllowed(it) },
                    modifier = Modifier
                        .testTag(CompactTags.ALLOW_TOGGLE)
                        .semantics { contentDescription = allowA11y },
                )
                Text(allowLabel, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // Form marker (✓ on / – off) carries the state without colour (WCAG 1.4.1); read-only, no switch.
            Row(
                modifier = Modifier.testTag(CompactTags.ALLOW_CHIP),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(if (allowed) "✓" else "–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(allowLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // §3-1 mandatory disclosure — ALWAYS visible (not only when on): the honest, irreversible consequence.
        TonedHint(stringResource(Res.string.compact_allow_hint), HintTone.INFO, CompactTags.ALLOW_HINT)

        // Non-operator: honest "only the operator can change this" (reuse workspace_operator_only, GATED tone).
        if (!state.editable) {
            TonedHint(stringResource(Res.string.workspace_operator_only), HintTone.GATED, CompactTags.GATE_HINT)
        }

        HorizontalDivider()

        // §3-3 server-mirror facts — rendered ONLY when the server state resolved (status != null). UNKNOWN →
        // these rows are absent (never a defaulted idle/off).
        status?.let { s ->
            // CYP-327 Feature A: an operator edits the threshold (server-mirror write); a non-operator sees the
            // read-only value. The editable path reuses the same never-optimistic setConfig write as the checkbox.
            if (state.editable) {
                ThresholdEditor(
                    current = s.thresholdTokens,
                    confirmValue = state.thresholdSetConfirm,
                    onSave = viewModel::setThreshold,
                )
            } else {
                LabelValueRow(
                    label = stringResource(Res.string.compact_threshold_label),
                    value = formatCompactTokens(s.thresholdTokens),
                    tag = CompactTags.THRESHOLD,
                )
            }

            // CYP-329: the two operator-tunable timings (Stagger + Round gap), minutes in the UI / ms on the wire.
            // Same never-optimistic server-mirror discipline as the threshold: operator edits, member reads. Bounds
            // come single-sourced from CompactConfig.companion — no hardcoded limits.
            if (state.editable) {
                TimingEditor(
                    label = stringResource(Res.string.compact_stagger_label),
                    a11y = stringResource(Res.string.a11y_compact_stagger_input),
                    currentMs = s.staggerMs,
                    minMs = CompactConfig.STAGGER_MIN_MS,
                    maxMs = CompactConfig.STAGGER_MAX_MS,
                    confirmMs = state.staggerSetConfirm,
                    inputTag = CompactTags.STAGGER_INPUT,
                    setTag = CompactTags.STAGGER_SET,
                    errorTag = CompactTags.STAGGER_ERROR,
                    confirmTag = CompactTags.STAGGER_CONFIRM,
                    onSaveMs = viewModel::setStaggerMs,
                )
                TimingEditor(
                    label = stringResource(Res.string.compact_round_gap_label),
                    a11y = stringResource(Res.string.a11y_compact_round_gap_input),
                    currentMs = s.roundGapMs,
                    minMs = CompactConfig.ROUND_GAP_MIN_MS,
                    maxMs = CompactConfig.ROUND_GAP_MAX_MS,
                    confirmMs = state.roundGapSetConfirm,
                    inputTag = CompactTags.ROUND_GAP_INPUT,
                    setTag = CompactTags.ROUND_GAP_SET,
                    errorTag = CompactTags.ROUND_GAP_ERROR,
                    confirmTag = CompactTags.ROUND_GAP_CONFIRM,
                    onSaveMs = viewModel::setRoundGapMs,
                )
            } else {
                LabelValueRow(
                    label = stringResource(Res.string.compact_stagger_label),
                    value = formatCompactDuration(s.staggerMs),
                    tag = CompactTags.STAGGER,
                )
                LabelValueRow(
                    label = stringResource(Res.string.compact_round_gap_label),
                    value = formatCompactDuration(s.roundGapMs),
                    tag = CompactTags.ROUND_GAP,
                )
            }

            val statusText = when {
                !s.allowed -> stringResource(Res.string.compact_status_off)
                s.running -> stringResource(Res.string.compact_status_running, s.lastRun?.startedTs?.let { formatLocalHhMmSsMillis(it) } ?: "—")
                else -> stringResource(Res.string.compact_status_idle)
            }
            LabelValueRow(
                label = stringResource(Res.string.compact_status_label),
                value = statusText,
                tag = CompactTags.STATUS,
                // Announce idle→running→done transitions even when focus is elsewhere.
                rowModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            // §1.3 last run — only a FINISHED run (finishedTs != null); an in-flight run shows via the status row.
            s.lastRun?.takeIf { it.finishedTs != null }?.let { run ->
                val timedOut = run.pendingAgentIds.isNotEmpty()
                // CYP-326 kill switch: an ABORTED run (allowed→false mid-run) is DISTINCT from a timeout — labelled
                // "abgebrochen", not "Timeout", never success. Both an abort and a timeout are incomplete → WARN amber;
                // a clean full run stays neutral (never green). Abort takes precedence over the timeout phrasing.
                val text =
                    if (run.aborted) {
                        stringResource(Res.string.compact_last_run_aborted, run.completed, run.total)
                    } else if (timedOut) {
                        stringResource(Res.string.compact_last_run_timeout, run.completed, run.total, run.pendingAgentIds.size)
                    } else {
                        stringResource(Res.string.compact_last_run_ok, run.completed, run.total)
                    }
                // §3-4 incomplete ≠ success: WARN amber (shared severity role, no hardcode); a full run stays neutral.
                val color = if (run.aborted || timedOut) severityColor(Severity.WARN) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag(CompactTags.LAST_RUN),
                )
            }
        }

        // CYP-327 Feature B: the current/last run's compact events, scoped AUTHORITATIVELY by the run's
        // correlationId (`status.lastRun.correlationId`, server-stamped on each event — no client heuristic). `null`
        // → no section (non-operator). Dynamic honest header ("current" while running, else "last"); a run has a
        // bounded event count so no panel-scroll rework is needed. Rolls to the next run automatically.
        compactEvents?.let { all ->
            HorizontalDivider()
            // CYP-328: derive the current run's id from the LIVE feed first (see [currentRunId]) so the list appears
            // the instant events stream — latency-free, without waiting for the status poll or a config POST.
            val runId = currentRunId(all, status)
            val runEvents = if (runId != null) all.filter { it.correlationId == runId } else emptyList()
            if (runId != null && runEvents.isNotEmpty()) {
                val running = status?.running == true
                Text(
                    text = stringResource(if (running) Res.string.compact_run_current else Res.string.compact_run_last),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth().testTag(CompactTags.RUN_HEADER),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().testTag(CompactTags.EVENTS),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    runEvents.forEachIndexed { i, e ->
                        val rowTag = CompactTags.eventRow(i)
                        // rowTag = the index tag; qualifierTag carries the severity suffix (EventBrowse convention) so
                        // the two testTags EventRow applies are DISTINCT (no duplicate-tag node).
                        EventRow(
                            event = e,
                            rowTag = rowTag,
                            qualifierTag = "$rowTag.${e.severity.name.lowercase()}",
                            byIdTag = "compact.event.byId.${e.id}",
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(Res.string.compact_events_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().testTag(CompactTags.EVENTS_EMPTY),
                )
            }
        }
    }
}

/**
 * CYP-328 — the current/last run's correlationId, the seam the per-sequence event list scopes to. Derived from the
 * LIVE feed FIRST (newest compact event by `seq` → its correlationId), falling back to the server status'
 * `lastRun.correlationId` when no compact event has been seen. Two compact runs never overlap (CYP-326), so the
 * newest-by-seq event belongs to the single active/last run — no decoy leak from an older finished run (lower seq)
 * — and the list appears the instant events stream, without waiting for the status poll or a config POST.
 *
 * `internal` so the CYP-328 runId tooth (MUT-B) targets THIS exact derivation seam, not a panel line. The
 * authoritative correlationId join (`filter { it.correlationId == runId }`) and the 4-type whitelist (the caller's
 * feed excludes `COMPACT_TRIGGERED`) are unchanged.
 */
internal fun currentRunId(events: List<Event>, status: CompactStatus?): String? =
    events.maxByOrNull { it.seq }?.correlationId ?: status?.lastRun?.correlationId

/**
 * CYP-327 Feature A — the operator's editable threshold (raw PO-context token count). Prefilled from the
 * server value and RE-SEEDED whenever it changes (`remember(current)`), so the field mirrors the server, never
 * an optimistic local value. Save is enabled only for a valid (positive) AND changed value; on save the write
 * goes through the same server-mirror [CompactViewModel.setThreshold]. Digits-only, bounded length.
 *
 * (Styling — plain number field + Save, house `SettingsPanel` idiom — is a placeholder pending the CYP-327 UIUX
 * spec; a stepper / compact-format input would be a swap here, no VM/contract change.)
 */
@Composable
private fun ThresholdEditor(current: Int, confirmValue: Int?, onSave: (Int) -> Unit) {
    // Draft, RE-SEEDED from the server value whenever it changes (`remember(current)`) — the field mirrors the
    // server after a confirmed set, never an optimistic local value. The drafted number is NOT live until Set +
    // server confirmation.
    var draft by remember(current) { mutableStateOf(current.toString()) }
    val parsed = draft.toIntOrNull()
    val valid = parsed != null && parsed in 1..1_000_000 // consistent with the ~1M formatCompactTokens window
    val invalidDraft = draft.isNotEmpty() && !valid
    val label = stringResource(Res.string.compact_threshold_label)
    val inputA11y = stringResource(Res.string.a11y_compact_threshold_input)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { new -> draft = new.filter { it.isDigit() }.take(7) }, // 1,000,000 = 7 digits
                singleLine = true,
                isError = invalidDraft,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag(CompactTags.THRESHOLD_INPUT)
                    .semantics { contentDescription = inputA11y },
            )
            // Live preview: the exact formatted value (honest — "= 750K" while the raw number is typed). "=" is
            // punctuation, not a localized string.
            if (parsed != null) {
                Text(
                    "= ${formatCompactTokens(parsed)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { parsed?.let(onSave) },
                // Enabled only for a VALID (1..1M) AND CHANGED value — no invalid submit, no no-op write.
                enabled = valid && parsed != current,
                modifier = Modifier.testTag(CompactTags.THRESHOLD_SET),
            ) {
                Text(stringResource(Res.string.compact_threshold_set))
            }
        }
        // Inline range validation (never colour alone — an ERROR-toned text hint).
        if (invalidDraft) {
            TonedHint(stringResource(Res.string.compact_threshold_range_error), HintTone.ERROR, CompactTags.THRESHOLD_ERROR)
        }
        // Transient INFO confirmation on the server-confirmed value — post-server, self-clearing, never green.
        confirmValue?.let {
            TonedHint(
                stringResource(Res.string.compact_threshold_set_confirm, formatCompactTokens(it)),
                HintTone.INFO,
                CompactTags.THRESHOLD_CONFIRM,
            )
        }
    }
}

/**
 * CYP-329 — an operator's editable compact timing (Stagger / Round gap). Structurally identical to
 * [ThresholdEditor], differing only in the UNIT: the field is **minutes** (re-seeded from the server ms value via
 * `remember(currentMs)` so it mirrors the server, never an optimistic draft), the live preview and range-error
 * echo the canonical duration, and Save is enabled only for a valid, changed value. Bounds [minMs]..[maxMs] are the
 * SINGLE-SOURCED `CompactConfig` consts (passed in — no hardcoded limits); on save the drafted minutes convert to
 * ms at [minutesFieldToMs] and go through the never-optimistic [onSaveMs].
 */
@Composable
private fun TimingEditor(
    label: String,
    a11y: String,
    currentMs: Long,
    minMs: Long,
    maxMs: Long,
    confirmMs: Long?,
    inputTag: String,
    setTag: String,
    errorTag: String,
    confirmTag: String,
    onSaveMs: (Long) -> Unit,
) {
    // Draft in minutes, RE-SEEDED from the server ms value whenever it changes — mirrors the server, never optimistic.
    var draft by remember(currentMs) { mutableStateOf(msToMinutesField(currentMs)) }
    val parsedMs = minutesFieldToMs(draft)
    val valid = parsedMs != null && parsedMs in minMs..maxMs
    val invalidDraft = draft.isNotEmpty() && !valid
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { new -> draft = sanitizeMinutesInput(new) },
                singleLine = true,
                isError = invalidDraft,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .weight(1f)
                    .testTag(inputTag)
                    .semantics { contentDescription = a11y },
            )
            // Live preview: the canonical duration the drafted minutes resolve to ("= 30 s" / "= 2 min").
            if (parsedMs != null) {
                Text(
                    "= ${formatCompactDuration(parsedMs)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { parsedMs?.let(onSaveMs) },
                enabled = valid && parsedMs != currentMs, // valid AND changed — no invalid submit, no no-op write
                modifier = Modifier.testTag(setTag),
            ) {
                Text(stringResource(Res.string.compact_threshold_set))
            }
        }
        // Inline range validation from the single-sourced bounds (never colour alone — an ERROR-toned hint).
        if (invalidDraft) {
            TonedHint(
                stringResource(Res.string.compact_timing_range_error, formatCompactDuration(minMs), formatCompactDuration(maxMs)),
                HintTone.ERROR,
                errorTag,
            )
        }
        // Transient INFO confirmation on the SERVER-confirmed value — post-server, self-clearing, never green.
        confirmMs?.let {
            TonedHint(
                stringResource(Res.string.compact_timing_set_confirm, formatCompactDuration(it)),
                HintTone.INFO,
                confirmTag,
            )
        }
    }
}

/** A read-only fact row: `onSurfaceVariant` label + `onSurface` value. [rowModifier] carries per-row semantics. */
@Composable
private fun LabelValueRow(label: String, value: String, tag: String, rowModifier: Modifier = Modifier) {
    Row(
        modifier = rowModifier.fillMaxWidth().testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}
