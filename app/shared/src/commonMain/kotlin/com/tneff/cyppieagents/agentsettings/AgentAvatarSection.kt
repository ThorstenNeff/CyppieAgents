package com.tneff.cyppieagents.agentsettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tneff.cyppieagents.ui.AgentAvatarView
import com.tneff.cyppieagents.ui.HintTone
import com.tneff.cyppieagents.ui.LocalAvatarBaseUrl
import com.tneff.cyppieagents.ui.LocalAvatarImageLoader
import com.tneff.cyppieagents.ui.TonedHint
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kmpcyppieagents.app.shared.generated.resources.Res
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_avatar_credits
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_avatar_current
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_avatar_preset
import kmpcyppieagents.app.shared.generated.resources.a11y_agent_avatar_remove
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_credit_line
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_credit_modified
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_credits
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_crop_hint
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_preset_label
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_remove
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_section
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_shuffle
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_style_adventurer
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_style_avataaars
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_style_big_smile
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_style_bottts
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_style_fun_emoji
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_upload
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_upload_generic_error
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_upload_size_error
import kmpcyppieagents.app.shared.generated.resources.agent_avatar_upload_type_error
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * CYP-216 §3 — the avatar section of the settings panel (extends the CYP-209 panel). Current avatar + remove, the
 * DiceBear **preset grid** (previews via the same-origin CYP-219 route, §3.2), custom **upload** (byte-acquisition is
 * the platform picker seam — the button invokes [onRequestUpload]; the VM does the pre-check + upload), and the
 * **credits** for the CC-BY styles (§3.4). Operator-gated: a non-operator sees the current avatar + credits read-only.
 */

/** Static per-style license data (from `docs/design/agent-avatar-tokens.json` styles[]) — names/URLs are DATA, not keys. */
data class AvatarStyleInfo(
    val style: String,
    val label: StringResource,
    val artist: String,
    val license: String,
    val licenseUrl: String,
    val attributionRequired: Boolean,
    val modifiedSuffix: Boolean,
)

val AVATAR_STYLES: List<AvatarStyleInfo> = listOf(
    AvatarStyleInfo("bottts", Res.string.agent_avatar_style_bottts, "Pablo Stanley", "Free for personal and commercial use", "https://bottts.com/", false, false),
    AvatarStyleInfo("avataaars", Res.string.agent_avatar_style_avataaars, "Pablo Stanley", "Free for personal and commercial use", "https://avataaars.com/", false, false),
    AvatarStyleInfo("adventurer", Res.string.agent_avatar_style_adventurer, "Lisa Wischofsky", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", true, true),
    AvatarStyleInfo("big-smile", Res.string.agent_avatar_style_big_smile, "Ashley Seo", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", true, true),
    AvatarStyleInfo("fun-emoji", Res.string.agent_avatar_style_fun_emoji, "Davis Uche", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", true, true),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentAvatarSection(viewModel: AgentSettingsViewModel, onRequestUpload: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxWidth().testTag(AgentSettingsTags.AVATAR_SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(Res.string.agent_avatar_section))

        // --- Current avatar + remove (§3.1). The stage semantics are DTO-derived (QA-3, no pixel peek). ---
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val currentCd = stringResource(Res.string.a11y_agent_avatar_current, state.effectiveStage.name.lowercase())
            Box(
                modifier = Modifier
                    .testTag(AgentSettingsTags.AVATAR_CURRENT)
                    .semantics {
                        contentDescription = currentCd
                        stateDescription = state.effectiveStage.name.lowercase() // image|preset|initials|color
                    },
            ) {
                AgentAvatarView(
                    id = state.id,
                    size = 56.dp,
                    displayName = state.name,
                    role = state.role,
                    colorHex = state.colorHex.ifBlank { null },
                    avatar = state.avatar,
                )
            }
            if (state.editable && state.avatar != null) {
                val removeCd = stringResource(Res.string.a11y_agent_avatar_remove)
                TextButton(
                    onClick = { viewModel.clearAvatar() },
                    enabled = !state.avatarBusy,
                    modifier = Modifier.testTag(AgentSettingsTags.AVATAR_REMOVE).semantics { contentDescription = removeCd },
                ) { Text(stringResource(Res.string.agent_avatar_remove)) }
            }
        }

        // --- Preset grid (§3.2): a CYP-219 preview per curated style + friendly label + selection ring. ---
        if (state.editable) {
            Text(stringResource(Res.string.agent_avatar_preset_label), style = MaterialTheme.typography.labelSmall)
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag(AgentSettingsTags.AVATAR_PRESET),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AVATAR_STYLES.forEach { info ->
                    PresetCell(
                        info = info,
                        agentId = state.id,
                        selected = state.selectedStyle == info.style,
                        enabled = !state.avatarBusy,
                        onClick = { viewModel.selectPreset(info.style) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.selectedStyle != null) {
                    TextButton(
                        onClick = { viewModel.shuffle() },
                        enabled = !state.avatarBusy,
                        modifier = Modifier.testTag(AgentSettingsTags.AVATAR_SHUFFLE),
                    ) { Text(stringResource(Res.string.agent_avatar_shuffle)) }
                }
                TextButton(
                    onClick = onRequestUpload,
                    enabled = !state.avatarBusy,
                    modifier = Modifier.testTag(AgentSettingsTags.AVATAR_UPLOAD),
                ) { Text(stringResource(Res.string.agent_avatar_upload)) }
            }
            TonedHint(stringResource(Res.string.agent_avatar_crop_hint), HintTone.INFO, AgentSettingsTags.AVATAR_CROP_HINT)
            // Honest server/pre-check rejection (§3.3 / §8) — the UI mirrors it, never a silent accept.
            state.avatarError?.let { err ->
                val msg = when (err) {
                    AvatarUploadError.TYPE -> stringResource(Res.string.agent_avatar_upload_type_error)
                    AvatarUploadError.SIZE -> stringResource(Res.string.agent_avatar_upload_size_error, AgentSettingsViewModel.MAX_UPLOAD_LABEL)
                    AvatarUploadError.GENERIC -> stringResource(Res.string.agent_avatar_upload_generic_error)
                }
                TonedHint(msg, HintTone.ERROR, AgentSettingsTags.AVATAR_UPLOAD_ERROR)
            }
        }

        // --- Credits (§3.4): one addressable line per attributed (CC-BY) style. ---
        val credited = AVATAR_STYLES.filter { it.attributionRequired }
        if (credited.isNotEmpty()) {
            val creditsCd = stringResource(Res.string.a11y_agent_avatar_credits)
            Column(
                modifier = Modifier.testTag(AgentSettingsTags.AVATAR_CREDITS).semantics { contentDescription = creditsCd },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(stringResource(Res.string.agent_avatar_credits), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                credited.forEach { info ->
                    val modified = if (info.modifiedSuffix) " (" + stringResource(Res.string.agent_avatar_credit_modified) + ")" else ""
                    Text(
                        text = stringResource(Res.string.agent_avatar_credit_line, stringResource(info.label), info.artist, info.license) + modified,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(AgentSettingsTags.avatarCreditEntry(info.style)),
                    )
                }
            }
        }
    }
}

/** A preset preview cell: the CYP-219 same-origin preview PNG over a placeholder (404/error → placeholder + label). */
@Composable
private fun PresetCell(info: AvatarStyleInfo, agentId: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val loader = LocalAvatarImageLoader.current
    val base = LocalAvatarBaseUrl.current
    // SECURITY #1: the ONLY preview source is the same-origin CYP-219 route — never api.dicebear.com; id/style/seed
    // URL-encoded (no scheme/host/path injection). seed = the agent id (the deterministic default preview seed).
    val model: String? = if (loader != null && base != null) {
        "$base/api/agents/${agentId.encodeURLPathPart()}/avatar/preview" +
            "?style=${info.style.encodeURLParameter()}&seed=${agentId.encodeURLParameter()}"
    } else {
        null
    }
    val label = stringResource(info.label)
    val cd = stringResource(Res.string.a11y_agent_avatar_preset, label)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant) // placeholder shows on 404/while loading
                .border(
                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(8.dp),
                )
                .testTag(AgentSettingsTags.avatarPresetStyle(info.style))
                .semantics { contentDescription = cd; this.selected = selected }
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (model != null && loader != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    imageLoader = loader,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                )
            }
            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
}
