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

    // CYP-315 — read-only absolute worktree path (server-resolved) + copy affordance (spec §5, shared QA/CYP-7).
    /** The rendered absolute path value (monospace, horizontally scrollable) — Z1 only. */
    const val WORKTREE_PATH = "agentSettings.worktree.path"
    /** The copy-to-clipboard icon-button — Z1 only. */
    const val WORKTREE_COPY = "agentSettings.worktree.copy"
    /** The transient INFO "path copied" confirmation (Z3). */
    const val WORKTREE_COPIED = "agentSettings.worktree.copied"
    /** The INFO "not local" hint — Z2 only (detail resolved AND `worktreePath == null`), NEVER on an unresolved load. */
    const val WORKTREE_NOT_LOCAL = "agentSettings.worktree.notLocal"

    const val COLOR = "agentSettings.color"
    /** One per palette slot; [index] = 0..7 (punktfrei — the palette order). */
    fun swatch(index: Int) = "agentSettings.swatch.$index"
    const val CUSTOM_HEX_INPUT = "agentSettings.customHex.input"
    const val CUSTOM_HEX_ERROR = "agentSettings.customHex.error"
    const val CONTRAST_ADVISORY = "agentSettings.contrastAdvisory"
    const val PREVIEW = "agentSettings.preview"

    // CYP-310 — the persona field is now the LIVE worktree CLAUDE.md (live GET + explicit hard "Überschreiben").
    // `PERSONA_INPUT` (the field) + `EFFECT_HINT` (restart) are REUSED; the rest are new (spec §8, QA/CYP-7-shared).
    const val PERSONA_INPUT = "agentSettings.persona.input"
    /** The "CLAUDE.md überschreiben" button (Layer-1 hard-overwrite affordance). */
    const val PERSONA_OVERWRITE = "agentSettings.persona.overwrite"
    /** Dirty disclosure — the buffer ≠ the file (Hop ① open, S2). */
    const val PERSONA_UNSAVED = "agentSettings.persona.unsaved"
    /** Settled-EMPTY — the file loaded OK but is absent (S4; distinct from a load error). */
    const val PERSONA_EMPTY = "agentSettings.persona.empty"
    /** Live-read FAILED (S5, fail-closed) — the shared LoadErrorRetry surface + its retry. */
    const val PERSONA_LOAD_ERROR = "agentSettings.persona.loadError"
    const val PERSONA_LOAD_ERROR_RETRY = "agentSettings.persona.loadError.retry"
    /** Layer-2 409-stale conflict dialog + its two actions ([Trotzdem überschreiben] / [Live-Version laden]). */
    const val PERSONA_CONFLICT = "agentSettings.persona.conflict"
    const val PERSONA_CONFLICT_OVERWRITE = "agentSettings.persona.conflict.overwrite"
    const val PERSONA_CONFLICT_RELOAD = "agentSettings.persona.conflict.reload"
    /** Overwrite FAILED (S6, non-stale) — stays dirty, no restart hint. */
    const val PERSONA_SAVE_ERROR = "agentSettings.persona.saveError"
    /** EFFECT_DEFERRED restart hint — shown ONLY after a successful CLAUDE.md overwrite (Hop ②: file ≠ running agent). */
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
