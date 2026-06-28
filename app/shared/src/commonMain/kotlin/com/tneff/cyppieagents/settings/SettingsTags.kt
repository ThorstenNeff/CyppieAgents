package com.tneff.cyppieagents.settings

/**
 * `testTag` contract for the Project-Settings panel (CYP-84 repo-config + CYP-85 API-key), exactly per
 * `docs/design/project-settings-tags.md` (Epic CYP-75). Test-Contract v0.5 §2: prefixless
 * `<area>[.<scopeId>].<element>`, segment values `[A-Za-z0-9-]+` (camelCase, no dots). Area `settings`,
 * single-instance. **Shared API with QA (CYP-7) — do not rename silently; coordinate via the PO.**
 *
 * The window mounts under the existing `window.settings.content` ([com.tneff.cyppieagents.window.WindowTestTags]);
 * the API-key effect activates via the existing CYP-73 per-agent restart (`agent.<id>.restartBtn`) — this
 * panel renders no restart control of its own (PROJECT-SETTINGS §5).
 */
object SettingsTags {
    const val AREA = "settings"

    const val PANEL = "settings.panel"
    const val SECTION_REPO = "settings.section.repo"
    const val SECTION_API_KEY = "settings.section.apiKey"

    // CYP-84 — Repo-Config
    const val REPO_URL_INPUT = "settings.repo.url.input"
    const val REPO_BRANCH_INPUT = "settings.repo.branch.input"
    const val REPO_SAVE = "settings.repo.save"
    const val REPO_STATUS = "settings.repo.status"
    const val REPO_GATE_HINT = "settings.repo.gateHint"
    const val REPO_EFFECT_HINT = "settings.repo.effectHint"
    const val REPO_ERROR = "settings.repo.error"

    // CYP-85 — API-Key
    const val API_KEY_MASKED = "settings.apiKey.masked"
    const val API_KEY_INPUT = "settings.apiKey.input"
    const val API_KEY_REVEAL = "settings.apiKey.reveal"
    const val API_KEY_SAVE = "settings.apiKey.save"
    const val API_KEY_GATE_HINT = "settings.apiKey.gateHint"
    const val API_KEY_EFFECT_HINT = "settings.apiKey.effectHint"
    const val API_KEY_ERROR = "settings.apiKey.error"
}
