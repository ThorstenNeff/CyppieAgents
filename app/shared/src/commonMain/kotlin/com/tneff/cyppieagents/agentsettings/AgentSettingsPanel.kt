package com.tneff.cyppieagents.agentsettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.SenderPalette
import com.tneff.cyppieagents.model.deriveScheme
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.LoadErrorRetry
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.agent_add_persona_placeholder
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_conflict_body
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_conflict_title
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_empty
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_overwrite
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_overwrite_anyway
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_overwrite_note
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_reload_live
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_save_failed
import kmpcyppieagents.app.shared.generated.resources.agent_claudemd_unsaved
import kmpcyppieagents.app.shared.generated.resources.load_failed
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_color_custom
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_color_preview
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_color_swatch
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_display_name
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_add_persona
import kmpcyppieagents.app.shared.generated.resources.agent_add_persona_label
import kmpcyppieagents.app.shared.generated.resources.agent_cancel
import kmpcyppieagents.app.shared.generated.resources.agent_color_adjusted_hint
import kmpcyppieagents.app.shared.generated.resources.agent_color_custom_invalid
import kmpcyppieagents.app.shared.generated.resources.agent_color_custom_label
import kmpcyppieagents.app.shared.generated.resources.agent_color_palette_label
import kmpcyppieagents.app.shared.generated.resources.agent_color_section
import kmpcyppieagents.app.shared.generated.resources.agent_display_name_label
import kmpcyppieagents.app.shared.generated.resources.agent_edit_effect_hint
import kmpcyppieagents.app.shared.generated.resources.agent_id_stable_label
import kmpcyppieagents.app.shared.generated.resources.agent_save
import kmpcyppieagents.app.shared.generated.resources.agent_worktree_copied
import kmpcyppieagents.app.shared.generated.resources.agent_worktree_not_local
import kmpcyppieagents.app.shared.generated.resources.agent_worktree_path_label
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_worktree_copy
import kmpcyppieagents.app.shared.generated.resources.agent_settings_title
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** `#RRGGBB` for a Compose colour (the swatch/override wire form). */
private fun hexOf(c: Color): String = "#" + (c.toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

/**
 * CYP-211 — the per-agent settings overlay (spec §4): editable **display name** + read-only **id** (stable
 * identity), **colour** (8 palette swatches + custom hex with a contrast guard + a live derived preview), and
 * **CLAUDE.md persona** (restart-deferred via the reused [agent_edit_effect_hint]). Operator-gated; a non-operator
 * gets a read-only view. Honesty (§7): id ≠ name (rename cosmetic); name/colour immediate, persona deferred.
 *
 * CYP-237: [onSaved] fires once when a save SUCCEEDS (`state.saved` flips true) — the host closes the overlay AND
 * refreshes the agent list so the change is live without a page reload. [onDismiss] is cancel/backdrop only (no
 * refresh). A save that FAILS keeps the dialog open (state.saved stays false → onSaved never fires).
 */
@Composable
fun AgentSettingsPanel(
    viewModel: AgentSettingsViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {},
    onRequestUpload: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val scheme = deriveScheme(state.baseArgb)

    // CYP-237 close-on-save: a successful save sets state.saved → signal the host exactly once (it closes +
    // refreshes). `saved` is CONSUMED first (reset to false) — the overlay's VM is retained across close, so a
    // sticky true would re-fire onSaved at first composition when the SAME agent is reopened (instant-close race).
    // Consuming makes each real save a fresh false→true edge; a reopen with saved=false is inert.
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // The dialog renders in its own Compose window → re-apply enableTestTagsAsResourceId so tags resolve (F2).
        modifier = Modifier.enableTestTagsAsResourceId().testTag(AgentSettingsTags.PANEL),
        title = { Text(stringResource(Res.string.agent_settings_title, state.name)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!state.editable) {
                    TonedHint(stringResource(Res.string.workspace_operator_only), HintTone.GATED, AgentSettingsTags.GATE_HINT)
                }

                // --- Identity: editable display name + read-only id (§4.1) ---
                Heading(stringResource(Res.string.agent_display_name_label))
                val nameCd = stringResource(Res.string.a11y_agent_display_name) + " " + state.name
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(Res.string.agent_display_name_label)) },
                    singleLine = true,
                    enabled = state.editable,
                    modifier = Modifier.fillMaxWidth().testTag(AgentSettingsTags.NAME_INPUT)
                        .semantics { contentDescription = nameCd },
                )
                Text(
                    text = stringResource(Res.string.agent_id_stable_label) + ": " + state.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(AgentSettingsTags.ID_READONLY),
                )

                // CYP-315 — read-only absolute worktree path (identity block, directly under the ID line). Read-only
                // for operator + non-operator alike (spec §9: display, not a privileged action → no operator gate).
                WorktreePathSection(state)

                // --- Colour: palette swatches + custom hex + guard + live preview (§4.2/§4.3) ---
                Heading(stringResource(Res.string.agent_color_section))
                Column(Modifier.testTag(AgentSettingsTags.COLOR), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.agent_color_palette_label), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SenderPalette.swatches.forEachIndexed { index, sc ->
                            val hex = hexOf(sc.avatarFill)
                            val selected = state.colorHex.equals(hex, ignoreCase = true)
                            val swatchCd = stringResource(Res.string.a11y_agent_color_swatch, (index + 1).toString())
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(sc.avatarFill)
                                    // Selection is NOT colour-only: a ring + `selected` semantics (WCAG 1.4.1).
                                    .border(
                                        BorderStroke(if (selected) 3.dp else 1.dp, if (selected) sc.onAvatar else sc.borderColor),
                                        CircleShape,
                                    )
                                    .testTag(AgentSettingsTags.swatch(index))
                                    .semantics { contentDescription = swatchCd; this.selected = selected }
                                    .clickableIf(state.editable) { viewModel.pickSwatch(hex) },
                            ) {
                                if (selected) Text("✓", color = sc.onAvatar, modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    val customCd = stringResource(Res.string.a11y_agent_color_custom)
                    OutlinedTextField(
                        value = state.colorHex,
                        onValueChange = viewModel::setColorHex,
                        label = { Text(stringResource(Res.string.agent_color_custom_label)) },
                        singleLine = true,
                        enabled = state.editable,
                        isError = state.hexError,
                        modifier = Modifier.fillMaxWidth().testTag(AgentSettingsTags.CUSTOM_HEX_INPUT)
                            .semantics { contentDescription = customCd },
                    )
                    if (state.hexError) {
                        TonedHint(stringResource(Res.string.agent_color_custom_invalid), HintTone.ERROR, AgentSettingsTags.CUSTOM_HEX_ERROR)
                    } else if (scheme.adjusted) {
                        // Honest: the effective colour was nudged for readable contrast (§4.2). Rarely fires (§5.3).
                        TonedHint(stringResource(Res.string.agent_color_adjusted_hint), HintTone.INFO, AgentSettingsTags.CONTRAST_ADVISORY)
                    }
                    // Live preview of the DERIVED scheme (bg/text/border) — the real effect, not the raw input (§4.3).
                    val previewCd = stringResource(Res.string.a11y_agent_color_preview, state.name)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(scheme.background))
                            .border(BorderStroke(1.5.dp, Color(scheme.border)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag(AgentSettingsTags.PREVIEW)
                            .semantics { contentDescription = previewCd },
                    ) {
                        Text(state.name.ifBlank { state.id }, color = Color(scheme.onColor), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }

                // --- Persona / CLAUDE.md — LIVE file field + hard "Überschreiben" (CYP-310, spec S1-S6) ---
                Heading(stringResource(Res.string.agent_add_persona_label))
                ClaudeMdSection(state, viewModel)

                // CYP-216 §3: the avatar section (current + preset grid + upload/remove + credits).
                AgentAvatarSection(viewModel, onRequestUpload)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.save() },
                enabled = state.editable && !state.hexError && !state.saving,
                modifier = Modifier.testTag(AgentSettingsTags.SAVE),
            ) { Text(stringResource(Res.string.agent_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(AgentSettingsTags.CANCEL)) {
                Text(stringResource(Res.string.agent_cancel))
            }
        },
    )
    // CYP-310 Layer-2 (§5): the 409-stale conflict — a blocking confirm ONLY on a real, unseen external change.
    if (state.claudeMdStale) ClaudeMdConflictDialog(viewModel)
}

/**
 * CYP-310 — the live worktree CLAUDE.md (spec §2/§3, S1-S6): a live-file field + the Layer-1 hard-overwrite
 * affordance ("CLAUDE.md überschreiben" + "kein Merge" micro-line) + EXACTLY ONE disclosure in the hint zone
 * (§10-3). Fail-closed: a failed live read shows [LoadErrorRetry] (never an empty field — failed ≠ empty, §10-4)
 * and the overwrite is locked (never write from an unknown base). Operator-gated (non-operator = read-only view).
 */
@Composable
private fun ClaudeMdSection(state: AgentSettingsUiState, viewModel: AgentSettingsViewModel) {
    // S5 fail-closed: the live read FAILED → the shared error+retry surface, NOT an empty field.
    if (state.claudeMdError) {
        LoadErrorRetry(
            message = stringResource(Res.string.load_failed),
            onRetry = viewModel::loadClaudeMd,
            containerTag = AgentSettingsTags.PERSONA_LOAD_ERROR,
            retryTag = AgentSettingsTags.PERSONA_LOAD_ERROR_RETRY,
        )
        return
    }
    val personaCd = stringResource(Res.string.a11y_agent_add_persona)
    // The LIVE-file field. D1: while dirty the buffer is frozen (the VM only refreshes on load/reload) → typing is
    // never clobbered. Disabled while the base is still loading — never overwrite from an unknown base.
    OutlinedTextField(
        value = state.claudeMd,
        onValueChange = viewModel::setClaudeMd,
        label = { Text(stringResource(Res.string.agent_add_persona_label)) },
        placeholder = { Text(stringResource(Res.string.agent_add_persona_placeholder)) },
        singleLine = false,
        enabled = state.editable && !state.claudeMdLoading,
        modifier = Modifier.fillMaxWidth().testTag(AgentSettingsTags.PERSONA_INPUT)
            .semantics { contentDescription = personaCd },
    )
    // Layer 1 (ALWAYS, operator only): the hard-overwrite affordance carries the "replace, no merge" awareness
    // before every click. Disabled unless the field is dirty and the base read settled (fail-closed).
    if (state.editable) {
        Button(
            onClick = viewModel::overwriteClaudeMd,
            enabled = state.canOverwriteClaudeMd,
            modifier = Modifier.testTag(AgentSettingsTags.PERSONA_OVERWRITE),
        ) { Text(stringResource(Res.string.agent_claudemd_overwrite)) }
        Text(
            text = stringResource(Res.string.agent_claudemd_overwrite_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Hint zone — EXACTLY ONE disclosure (§10-3), by precedence: overwrite-error (S6, stays dirty, no restart) →
    // dirty (S2) → restart (S3/S4 post-success) → settled-empty (S4). Clean (S1) shows nothing.
    when {
        state.claudeMdWriteError ->
            TonedHint(stringResource(Res.string.agent_claudemd_save_failed), HintTone.ERROR, AgentSettingsTags.PERSONA_SAVE_ERROR)
        state.claudeMdDirty ->
            TonedHint(stringResource(Res.string.agent_claudemd_unsaved), HintTone.EFFECT_DEFERRED, AgentSettingsTags.PERSONA_UNSAVED)
        state.needsRestart ->
            TonedHint(stringResource(Res.string.agent_edit_effect_hint), HintTone.EFFECT_DEFERRED, AgentSettingsTags.EFFECT_HINT)
        state.claudeMdEmpty ->
            TonedHint(stringResource(Res.string.agent_claudemd_empty), HintTone.INFO, AgentSettingsTags.PERSONA_EMPTY)
    }
}

/** CYP-310 Layer-2 — the 409-stale conflict (§5): [Trotzdem überschreiben] (force with the fresh version) vs the
 *  safe [Live-Version laden] (discard local, reload). Dismiss = the safe reload. */
@Composable
private fun ClaudeMdConflictDialog(viewModel: AgentSettingsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::reloadLiveClaudeMd,
        // The dialog renders in its OWN window → re-apply so its testTags resolve as resource-ids for Maestro (F2).
        modifier = Modifier.enableTestTagsAsResourceId().testTag(AgentSettingsTags.PERSONA_CONFLICT),
        title = { Text(stringResource(Res.string.agent_claudemd_conflict_title)) },
        text = { Text(stringResource(Res.string.agent_claudemd_conflict_body)) },
        confirmButton = {
            TextButton(onClick = viewModel::forceOverwriteClaudeMd, modifier = Modifier.testTag(AgentSettingsTags.PERSONA_CONFLICT_OVERWRITE)) {
                Text(stringResource(Res.string.agent_claudemd_overwrite_anyway))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::reloadLiveClaudeMd, modifier = Modifier.testTag(AgentSettingsTags.PERSONA_CONFLICT_RELOAD)) {
                Text(stringResource(Res.string.agent_claudemd_reload_live))
            }
        },
    )
}

/** ~2 s transient window for the "path copied" receipt (§3). */
private const val WORKTREE_COPIED_MS = 2_000L

/**
 * CYP-315 (spec §1/§2) — the read-only ABSOLUTE worktree path in the identity block (under the ID line). The client
 * reads the typed [AgentSettingsUiState.worktreePath] straight off `AgentDetail` (server-resolved; never rebuilt).
 * Exactly one status line, by precedence:
 *  - **Z4** unresolved / failed detail load (`!detailResolved`) → render NOTHING. `worktreePath == null` is the client
 *    default and a failed load collapses to it too, so "not local" must hang on the POSITIVE [AgentSettingsUiState.
 *    detailResolved] signal — never bare `== null` (the CYP-288 `failed ≠ remote` honesty core, §2).
 *  - **Z2** resolved + `worktreePath == null` → the INFO "not local" hint; no path field, no copy button.
 *  - **Z1** resolved + a path → monospace, single-line, horizontally-scrollable value (unbroken) inside a
 *    [SelectionContainer] (always-available manual copy) + a trailing copy icon-button → transient INFO "path copied"
 *    (§3; `liveRegion=Polite` so the receipt is announced). Both hints are INFO/blue — no green SUCCESS (§6).
 */
// `internal` (not `private`) so the clipboard tooth can render the section directly — the full panel wraps it in an
// AlertDialog (its own composition window) which does NOT inherit a test-provided `LocalClipboardManager`.
@Composable
internal fun WorktreePathSection(state: AgentSettingsUiState) {
    // Z4: detail not resolved (still loading OR a failed load that silently collapsed to null) → stay silent; never
    // claim "not local" from an unknown state (§2 honesty core; §8-3 invariant).
    if (!state.detailResolved) return

    val path = state.worktreePath
    val clipboard = LocalClipboardManager.current
    // Re-arm per agent (`state.id`) so a reused panel never carries a stale "copied" receipt across an agent switch.
    var copied by remember(state.id) { mutableStateOf(false) }
    var copyTick by remember(state.id) { mutableStateOf(0) }

    // Label row: "Worktree-Pfad" + (Z1 only) the trailing copy icon-button.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(Res.string.agent_worktree_path_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (path != null) {
            val copyCd = stringResource(Res.string.a11y_agent_worktree_copy)
            IconButton(
                // Truthful copy (§3/§6/§8-6): actually invoke the clipboard, THEN show the receipt. `setText` is
                // synchronous on Desktop/Android; on Web the async/permission-gated API may no-op — the
                // SelectionContainer below is the always-available manual fallback (never fake beyond "copy fired").
                onClick = {
                    clipboard.setText(AnnotatedString(path))
                    copied = true
                    copyTick++
                },
                modifier = Modifier
                    .testTag(AgentSettingsTags.WORKTREE_COPY)
                    .semantics { contentDescription = copyCd },
            ) {
                // No material-icons-extended in `:app` → a plain glyph is the visible affordance; the a11y name
                // (contentDescription above) carries the meaning (colour/glyph never the sole carrier, §6 / WCAG 1.4.1).
                Text("⧉", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (path != null) {
        // The value: monospace, ONE line, horizontally scrollable — a long path scrolls, never wraps/breaks layout,
        // and stays an integral string (§1/§8-2). SelectionContainer keeps manual select-copy available everywhere.
        SelectionContainer {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(AgentSettingsTags.WORKTREE_PATH),
                )
            }
        }
    }

    // EXACTLY ONE status line (§2/§8-4): Z2 "not local" (resolved + null path) XOR Z3 transient "copied" (Z1 after a
    // copy). Mutually exclusive by construction (null vs non-null `path`); both INFO/blue — no green SUCCESS (§6).
    when {
        path == null ->
            TonedHint(stringResource(Res.string.agent_worktree_not_local), HintTone.INFO, AgentSettingsTags.WORKTREE_NOT_LOCAL)
        copied ->
            TonedHint(
                text = stringResource(Res.string.agent_worktree_copied),
                tone = HintTone.INFO,
                tag = AgentSettingsTags.WORKTREE_COPIED,
                // Polite live-region so the transient receipt is announced to screen readers (§3 / §8-7).
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
    }

    // Z3 self-clear: the receipt fades ~2 s after the copy; a re-copy bumps `copyTick` → cancels + restarts the timer.
    if (copied) {
        LaunchedEffect(copyTick) {
            delay(WORKTREE_COPIED_MS)
            copied = false
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

/** Apply [onClick] only when [enabled] (a non-operator's read-only view does not react). */
private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this
