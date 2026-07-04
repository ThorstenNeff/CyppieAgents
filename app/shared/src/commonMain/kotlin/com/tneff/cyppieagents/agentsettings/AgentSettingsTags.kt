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

    const val SAVE = "agentSettings.save"
    const val CANCEL = "agentSettings.cancel"
}
