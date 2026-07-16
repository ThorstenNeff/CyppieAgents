package com.tneff.cyppieagents.firstrun

/**
 * CYP-629 — testTags for the First-Run gate (Test-Contract v0.5 §2: prefixless `<area>[.<scopeId>].<element>`,
 * camelCase segment values). Area `firstRun`, single-instance. Shared API with QA (CYP-7) — additive, never
 * silently renamed. Frozen names from `docs/design/first-run-setup-tags.md`. (Only the tags wired so far live
 * here; the rest land with their surfaces in later sub-slices.)
 */
object FirstRunTags {
    const val GATE = "firstRun.gate"
    const val INTRO = "firstRun.intro"
    const val DEGRADED_NOTE = "firstRun.degradedNote"
    const val STEPPER = "firstRun.stepper"
    const val STEP_API_KEY = "firstRun.step.apiKey"
    const val STEP_REPO = "firstRun.step.repo"
    const val STEP_TEAM = "firstRun.step.team"
    const val APIKEY_POSTURE = "firstRun.apiKey.posture"
    const val APIKEY_SAVED = "firstRun.apiKey.saved"
    const val REPO_SAVED = "firstRun.repo.saved"
    const val TEAM_INTRO = "firstRun.team.intro"
    const val SKIP = "firstRun.skip"
    const val COMPLETE = "firstRun.complete"
    const val OPEN_WORKSPACE = "firstRun.openWorkspace"
}
