package com.tneff.cyppieagents.compact

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * CYP-329 — the minutes↔milliseconds seam for the compact-timing editors. The UI works in **minutes** (the
 * operator-facing unit, `%s (min)` labels), the wire/`:core` contract is in **ms**; these convert at exactly one
 * place so the two never drift. `internal` so the conversion teeth target them directly.
 */
internal const val MS_PER_MIN = 60_000L

/**
 * ms → the minutes text to prefill the editor. Whole minutes render without a decimal (`120_000` → `"2"`), a
 * clean half renders compactly (`30_000` → `"0.5"`); any other value rounds to at most 3 dp with trailing zeros
 * trimmed so a server value always round-trips to a tidy field. Mirrors the server value, never an optimistic draft.
 */
internal fun msToMinutesField(ms: Long): String {
    val mins = ms.toDouble() / MS_PER_MIN
    if (mins == floor(mins)) return mins.toLong().toString()
    // Round to 3 dp, then strip trailing zeros / dot (e.g. 1.500 → "1.5").
    val rounded = (mins * 1000).roundToLong() / 1000.0
    return rounded.toString().trimEnd('0').trimEnd('.')
}

/**
 * A minutes field (accepting `.` or `,` as the decimal mark) → ms, or `null` when it does not parse to a number.
 * Rounds to the nearest ms so `0.5` → `30_000`.
 */
internal fun minutesFieldToMs(field: String): Long? =
    field.replace(',', '.').toDoubleOrNull()?.let { (it * MS_PER_MIN).roundToLong() }

/**
 * Keep a minutes text field sane while typing: digits plus at most ONE decimal mark (`.`/`,` → `.`), bounded
 * length. Not a validator — range validation happens against the single-sourced bounds; this only stops garbage.
 */
internal fun sanitizeMinutesInput(s: String): String {
    val sb = StringBuilder()
    var seenDot = false
    for (c in s) {
        when {
            c.isDigit() -> sb.append(c)
            (c == '.' || c == ',') && !seenDot -> { seenDot = true; sb.append('.') }
        }
    }
    return sb.toString().take(6) // e.g. "30.000" — ample for the ≤30 min bounds
}

/**
 * ms → a compact, language-neutral duration for the live preview and the read-only rows: `"2 min"`,
 * `"30 s"`, `"1 min 30 s"`. `min`/`s` are unit abbreviations (like the token `k`/`M`), not localized copy.
 */
internal fun formatCompactDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return when {
        min > 0 && sec == 0L -> "$min min"
        min > 0 -> "$min min $sec s"
        else -> "$sec s"
    }
}
