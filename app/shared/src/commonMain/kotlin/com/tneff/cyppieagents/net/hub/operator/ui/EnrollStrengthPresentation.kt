package com.tneff.cyppieagents.net.hub.operator.ui

import com.tneff.cyppieagents.net.hub.operator.vault.StrengthVerdict

/**
 * CYP-542 / B1 (UIUX enroll-QA D1/D3, AC-3) — the **cause-specific** presentation of the enroll strength [verdict],
 * so the Compose dialog renders the ratified honesty ladder (not the generic local-error path):
 *  - **OK** → neutral, no block.
 *  - **TOO_WEAK** → **WARN-amber** (advisory, `▲` — like the session-only path), fixable guidance; still **blocks**
 *    enrollment (the ② floor is fail-closed, F-#4) — the amber tone is "strengthen it", not "proceed".
 *  - **BLOCKLISTED** → **ERROR** tone (definitively bad, common/breached), **blocks**; the meter fill is dampened
 *    (D5 — never render "strong").
 *
 * D3: the message keys are **setup-time `enrollError.*`** copy, distinct from the auth-time `error(cause)` taxonomy
 * (H1). The tone is the presentation; `blocks` is the fail-closed gate (both non-OK verdicts block, F-#4).
 */
data class EnrollStrengthUi(
    val tone: EnrollTone,
    val messageKey: String,
    val blocks: Boolean,
    /** D5 — the meter fill fraction hint (0f..1f); dampened for BLOCKLISTED so a common phrase never shows "strong". */
    val meterFraction: Float,
)

/** The enroll-strength tone (D1). WARN = amber advisory (`▲`); ERROR = error tone; NEUTRAL = no alarm. */
enum class EnrollTone { NEUTRAL, WARN, ERROR }

/** Map the vault [StrengthVerdict] to its ratified enroll presentation (D1/D3/D5). */
fun enrollStrengthUi(verdict: StrengthVerdict): EnrollStrengthUi = when (verdict) {
    StrengthVerdict.OK -> EnrollStrengthUi(EnrollTone.NEUTRAL, "enroll_strength_ok", blocks = false, meterFraction = 1f)
    StrengthVerdict.TOO_WEAK -> EnrollStrengthUi(EnrollTone.WARN, "enroll_error_too_weak", blocks = true, meterFraction = 0.25f)
    StrengthVerdict.BLOCKLISTED -> EnrollStrengthUi(EnrollTone.ERROR, "enroll_error_blocklisted", blocks = true, meterFraction = 0.1f)
}
