package com.tneff.cyppieagents.agentsettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tneff.cyppieagents.ui.SenderPalette
import com.tneff.cyppieagents.model.deriveScheme
import com.tneff.cyppieagents.testing.enableTestTagsAsResourceId
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.TonedHint
import kmpcyppieagents.app.shared.generated.resources.Res
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
import kmpcyppieagents.app.shared.generated.resources.agent_settings_title
import kmpcyppieagents.app.shared.generated.resources.workspace_operator_only
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

                // --- Persona (CLAUDE.md) — restart-deferred, reused effect hint (§4.4) ---
                Heading(stringResource(Res.string.agent_add_persona_label))
                val personaCd = stringResource(Res.string.a11y_agent_add_persona)
                OutlinedTextField(
                    value = state.persona,
                    onValueChange = viewModel::setPersona,
                    label = { Text(stringResource(Res.string.agent_add_persona_label)) },
                    singleLine = false,
                    enabled = state.editable,
                    modifier = Modifier.fillMaxWidth().testTag(AgentSettingsTags.PERSONA_INPUT)
                        .semantics { contentDescription = personaCd },
                )
                // The restart hint is shown ONLY AFTER a persona SAVE (saved ≠ active → restart) — never while merely
                // editing (the key says "Gespeichert…"; pre-save that would be a lie). Name/colour are immediate (§4.4/§7.3).
                if (state.needsRestart) {
                    TonedHint(stringResource(Res.string.agent_edit_effect_hint), HintTone.EFFECT_DEFERRED, AgentSettingsTags.EFFECT_HINT)
                }

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
