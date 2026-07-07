package com.tneff.cyppieagents.window

/**
 * CYP-316 — the compact context-token count for the agent-window title bar (UIUX spec §1). Pure/commonMain so
 * it is unit-testable and identical on every target. Rule:
 *  - `n < 1000` → exact (`"842"`)
 *  - `1_000 … 999_999` → integer-`k` (`"137k"`, `"8k"`)
 *  - `≥ 1_000_000` → one-decimal-`M` (`"1.2M"`)
 *
 * No thousands separators (the compact glyph is width-constrained; the a11y label + the optional hover tooltip
 * carry the exact/precise value). The megabyte tenth is **truncated**, not rounded — an honest under-report that
 * never OVERSTATES context occupancy. `k`/`M` are language-neutral (no visible text label, §1). Callers only
 * invoke this when the value is non-null (Z1); `null` (unknown) shows NOTHING, never `0` (the §8-3 honesty core).
 */
fun formatCompactTokens(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> "${n / 1_000}k"
    else -> {
        val tenths = n / 100_000 // e.g. 1_200_000 → 12 → "1.2M"; 1_000_000 → 10 → "1.0M" (truncated, never over)
        "${tenths / 10}.${tenths % 10}M"
    }
}
