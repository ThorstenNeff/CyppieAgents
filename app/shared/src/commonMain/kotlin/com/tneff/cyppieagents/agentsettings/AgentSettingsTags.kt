package com.tneff.cyppieagents.agentsettings

/**
 * CYP-211 — `testTag` contract for the per-agent settings panel (Test-Contract v0.5 §2, prefixless,
 * single-instance area `agentSettings`). Shared API with QA (CYP-7) — not renamed silently. The window
 * titlebar ⋮ button lives in `WindowTestTags.settings(id)` (window area).
 */
object AgentSettingsTags {
    const val AREA = "agentSettings"

    const val PANEL = "agentSettings.panel"
    const val NAME_INPUT = "agentSettings.name.input"
    const val ID_READONLY = "agentSettings.idReadonly"

    const val COLOR = "agentSettings.color"
    /** One per palette slot; [index] = 0..7 (punktfrei — the palette order). */
    fun swatch(index: Int) = "agentSettings.swatch.$index"
    const val CUSTOM_HEX_INPUT = "agentSettings.customHex.input"
    const val CUSTOM_HEX_ERROR = "agentSettings.customHex.error"
    const val CONTRAST_ADVISORY = "agentSettings.contrastAdvisory"
    const val PREVIEW = "agentSettings.preview"

    const val PERSONA_INPUT = "agentSettings.persona.input"
    /** EFFECT_DEFERRED restart hint — shown ONLY when the persona changed (name/colour are immediate). */
    const val EFFECT_HINT = "agentSettings.effectHint"
    const val GATE_HINT = "agentSettings.gateHint"

    // CYP-216 avatar section (shared with QA — docs/design/agent-avatar-tags.md / -qa.md).
    const val AVATAR_SECTION = "agentSettings.avatar.section"
    /** The effective avatar; carries a `stateDescription` = the fallback stage (QA-3, deterministic — no pixel peek). */
    const val AVATAR_CURRENT = "agentSettings.avatar.current"
    const val AVATAR_PRESET = "agentSettings.avatar.preset"
    /** One per curated style; [style] = the raw DiceBear key (punktfrei, e.g. `big-smile`). */
    fun avatarPresetStyle(style: String) = "agentSettings.avatar.presetStyle.$style"
    const val AVATAR_SHUFFLE = "agentSettings.avatar.shuffle"
    const val AVATAR_UPLOAD = "agentSettings.avatar.upload"
    const val AVATAR_REMOVE = "agentSettings.avatar.remove"
    const val AVATAR_CREDITS = "agentSettings.avatar.credits"
    /** One per attributed (CC-BY) style, so QA-7 can address each credit line. */
    fun avatarCreditEntry(style: String) = "agentSettings.avatar.credits.entry-$style"
    const val AVATAR_CROP_HINT = "agentSettings.avatar.cropHint"
    const val AVATAR_UPLOAD_ERROR = "agentSettings.avatar.uploadError"

    const val SAVE = "agentSettings.save"
    const val CANCEL = "agentSettings.cancel"
}
