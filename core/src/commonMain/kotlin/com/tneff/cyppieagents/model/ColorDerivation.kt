package com.tneff.cyppieagents.model

import kotlin.math.pow

/**
 * CYP-209/211 — deterministic, **compose-free** colour derivation shared by the server and every client (the same
 * `:core` split as [colorSlot]): a base identity colour → a WCAG-contrast-safe `{background, onColor, border}`
 * scheme. Same base → same scheme everywhere (no drift, no runtime seed). Operates on **opaque ARGB ints** (input
 * alpha ignored; output forced opaque). Contrast targets (CYP-14): text/onColor ≥ 4.5:1, border ≥ 3:1.
 *
 * `:app:shared` wraps the int results into Compose `Color`s (SenderColor.borderColor + the titlebar theming); the
 * backend can validate/derive with the SAME code. This module never touches Compose.
 */

/** The derived, contrast-safe scheme for a base identity colour (opaque `0xFFRRGGBB` ints). */
data class DerivedScheme(
    /** The base, minimally lightness-adjusted (hue preserved) so [onColor] reaches ≥ 4.5:1. */
    val background: Int,
    /** Text/icon on [background] — WHITE or BLACK, whichever contrasts more. */
    val onColor: Int,
    /** A visible edge — ≥ 3:1 against the neutral app surface. */
    val border: Int,
    /** True when [background] was nudged from the raw base for contrast → honest advisory (spec §4.2). */
    val adjusted: Boolean,
)

const val WHITE_ARGB: Int = 0xFFFFFFFF.toInt()
const val BLACK_ARGB: Int = 0xFF000000.toInt()

/** WCAG text contrast target (§5.3). */
const val CONTRAST_TEXT_MIN: Double = 4.5
/** WCAG border/large-element contrast target (§5.3). */
const val CONTRAST_BORDER_MIN: Double = 3.0

// The neutral M3 dark-default surface the window border must stay visible against (app is dark-default, CYP-14).
private const val NEUTRAL_SURFACE: Int = 0xFF1E1E1E.toInt()
private const val OPAQUE: Int = 0xFF000000.toInt()
private const val MAX_STEPS: Int = 24 // deterministic upper bound on the lightness-nudge ladder

private fun red(c: Int) = (c shr 16) and 0xFF
private fun green(c: Int) = (c shr 8) and 0xFF
private fun blue(c: Int) = c and 0xFF
private fun argb(r: Int, g: Int, b: Int): Int =
    OPAQUE or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or (b.coerceIn(0, 255))

/** WCAG relative luminance of an (A)RGB int (alpha ignored). */
fun relLuminance(color: Int): Double {
    fun lin(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * lin(red(color)) + 0.7152 * lin(green(color)) + 0.0722 * lin(blue(color))
}

/** WCAG contrast ratio ∈ [1, 21] between two colours (order-independent). */
fun contrastRatio(a: Int, b: Int): Double {
    val la = relLuminance(a)
    val lb = relLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** Scale a colour toward black by [factor] (0..1) — hue-preserving (all channels ×factor). */
private fun darken(c: Int, factor: Double): Int =
    argb((red(c) * factor).toInt(), (green(c) * factor).toInt(), (blue(c) * factor).toInt())

/** Lerp a colour toward white by [t] (0..1) — hue roughly preserved. */
private fun lighten(c: Int, t: Double): Int =
    argb(
        (red(c) + (255 - red(c)) * t).toInt(),
        (green(c) + (255 - green(c)) * t).toInt(),
        (blue(c) + (255 - blue(c)) * t).toInt(),
    )

/**
 * Derive the contrast-safe scheme for [base] (§5.3):
 *  1. [onColor] = the higher-contrast of WHITE/BLACK against [base].
 *  2. Self-correct: while `contrastRatio(onColor, background) < 4.5`, nudge the background lightness AWAY from mid
 *     (darker if onColor=WHITE, lighter if onColor=BLACK) in fixed steps — hue preserved — until the target is met
 *     or [MAX_STEPS] is hit (then the best-reached value + `adjusted=true`; the preview/advisory show the truth).
 *  3. [border] = the background pushed to reach ≥ 3:1 against the neutral surface (visible window edge).
 */
fun deriveScheme(base: Int): DerivedScheme {
    val onColor = if (contrastRatio(WHITE_ARGB, base) >= contrastRatio(BLACK_ARGB, base)) WHITE_ARGB else BLACK_ARGB
    var bg = base
    var adjusted = false
    var step = 0
    while (contrastRatio(onColor, bg) < CONTRAST_TEXT_MIN && step < MAX_STEPS) {
        bg = if (onColor == WHITE_ARGB) darken(base, 1.0 - 0.04 * (step + 1)) else lighten(base, 0.04 * (step + 1))
        adjusted = true
        step++
    }
    // Border: nudge toward onColor until it stands off the neutral surface (≥ 3:1), so the edge is visible.
    var border = bg
    var bstep = 0
    while (contrastRatio(border, NEUTRAL_SURFACE) < CONTRAST_BORDER_MIN && bstep < MAX_STEPS) {
        border = if (onColor == WHITE_ARGB) lighten(bg, 0.05 * (bstep + 1)) else darken(bg, 1.0 - 0.05 * (bstep + 1))
        bstep++
    }
    return DerivedScheme(background = bg, onColor = onColor, border = border, adjusted = adjusted)
}

/** Parse a `#RRGGBB` hex string to an opaque ARGB int, or null if malformed (the §4.2 format guard). */
fun parseHexColor(hex: String): Int? {
    val h = hex.trim()
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(h)) return null
    val rgb = h.substring(1).toLong(16).toInt()
    return OPAQUE or (rgb and 0xFFFFFF)
}
