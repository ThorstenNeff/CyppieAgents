package com.tneff.cyppieagents.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tneff.cyppieagents.eventlog.railColor
import com.tneff.cyppieagents.eventlog.severityColorFor
import com.tneff.cyppieagents.eventlog.severityContainerFor
import com.tneff.cyppieagents.model.Severity
import com.tneff.cyppieagents.model.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-359 — **the rule belongs to the colour PAIR, not to the role.**
 *
 * `outline` as a **container** behind `surface`-coloured content is the *same pair* as `outline` content on
 * `surface`: identical contrast (3.55:1 light / 3.63:1 dark), sides swapped. `OutlineTextColorGuardTest` looks
 * for `outline` as a **foreground** and is structurally blind to that — there, `outline` is the background.
 * `WindowBadge`'s DEBUG pill is exactly that case: `surface` glyph on an `outline` container. It passes (a glyph
 * is a graphical object, 3:1) — **but nothing would notice if it stopped passing.**
 *
 * No regex can decide whether a colour stands in front or behind. A **number** can. So this test enumerates the
 * pairs the UI actually produces and measures each one, in both themes. It is role-blind by construction:
 * contrast is symmetric, and [pairContrast_isSymmetric_soASwappedRoleCannotHide] pins that.
 *
 * **The two guards are complementary, neither replaces the other:**
 *  - the source guard carries the clause *"colours no text in human language"* — that **must** be
 *    identifier-based, because only a human sees whether a character sequence is language;
 *  - this test carries the clause *"≥ 3:1, or exempt with a named redundancy carrier"* — that must be
 *    value-based, because only a number sees a contrast.
 *
 * Uses `:core`'s [contrastRatio] — one source for the formula (CYP-358).
 */
class ContrastPairGuardTest {

    private val textMin = 4.5 // WCAG 1.4.3 — a sequence of characters expressing something in human language
    private val graphicMin = 3.0 // WCAG 1.4.11 — icons, glyphs, borders, meaningful boundaries

    /**
     * A colour pair the UI really renders, and the threshold it owes.
     *
     * [redundancyCarrier] is the ONLY way a pair may sit below its threshold: it names *what else* carries the
     * meaning. `null` means "this pair passes on the number". No exemption without a named carrier — an
     * exemption whose reason is a guess is a trap with an expiry date (CYP-345).
     */
    private data class RenderedPair(
        val name: String,
        val foreground: Color,
        val background: Color,
        val threshold: Double,
        val redundancyCarrier: String? = null,
    ) {
        val ratio: Double get() = contrastRatio(foreground.toArgb(), background.toArgb())
    }

    private fun hex(c: Color): String {
        val rgb = c.toArgb() and 0xFFFFFF
        return "#" + rgb.toString(16).uppercase().padStart(6, '0')
    }

    /** Every severity rendering, in one theme. These are pure functions of `(scheme, dark)` — enumerable. */
    private fun pairsFor(scheme: ColorScheme, dark: Boolean): List<RenderedPair> {
        val theme = if (dark) "dark" else "light"
        return Severity.entries.flatMap { sev ->
            val (container, onContainer) = severityContainerFor(sev, scheme, dark)
            listOf(
                // The severity GLYPH, painted on the plain surface (ProductLeadPanel, EventRowUi).
                RenderedPair(
                    "$theme severity glyph $sev on surface",
                    severityColorFor(sev, scheme, dark), scheme.surface, graphicMin,
                ),
                // The severity PILL: the glyph sits ON the container. For DEBUG this is (surface on outline) —
                // the roles-swapped twin of the three violations CYP-337 fixed, and the reason this test exists.
                RenderedPair(
                    "$theme severity pill $sev (glyph on container)",
                    onContainer, container, graphicMin,
                ),
                // The 4dp severity RAIL against the surface it is drawn on.
                RenderedPair(
                    "$theme severity rail $sev on surface",
                    sev.railColor(dark), scheme.surface, graphicMin,
                    redundancyCarrier = if (sev == Severity.DEBUG) {
                        "the `·` glyph AND the severity text label beside it — the rail is the third, redundant " +
                            "signal, deliberately the quietest (EventVisuals: 'DEBUG is deliberately dim in BOTH " +
                            "schemes'). NOTE: the DEBUG *glyph* and *pill* have no such carrier and pass on the number."
                    } else {
                        null
                    },
                ),
            )
        } + listOf(
            // The CYP-337 rule itself, as a pair: metadata text owes the TEXT threshold.
            RenderedPair("$theme metadata text (onSurfaceVariant on surface)", scheme.onSurfaceVariant, scheme.surface, textMin),
        )
    }

    private fun allPairs(): List<RenderedPair> = pairsFor(MaritimeLight, dark = false) + pairsFor(MaritimeDark, dark = true)

    @Test
    fun everyRenderedPair_meetsItsThreshold_orIsExemptWithANamedCarrier() {
        val failures = allPairs().filter { it.redundancyCarrier == null && it.ratio < it.threshold }
        assertTrue(
            failures.isEmpty(),
            "These rendered colour pairs are below the contrast they owe. The rule is about the PAIR, not the " +
                "role — a colour behind text fails exactly as a colour on text does.\n" +
                failures.joinToString("\n") {
                    "  ${it.name}: ${hex(it.foreground)} on ${hex(it.background)} = " +
                        "${(it.ratio * 100).toInt() / 100.0}:1, owes ${it.threshold}:1"
                },
        )
    }

    @Test
    fun everyExemptPair_actuallyNeedsItsExemption() {
        // A pair that passes on the number does not need a redundancy carrier, and keeping one pre-approves a
        // future dimming that nothing would then catch. Same disease as a stale allowlist entry (CYP-345).
        val unnecessary = allPairs().filter { it.redundancyCarrier != null && it.ratio >= it.threshold }
        assertTrue(
            unnecessary.isEmpty(),
            "These pairs carry a redundancy exemption but pass on the number — drop the exemption:\n" +
                unnecessary.joinToString("\n") { "  ${it.name} = ${(it.ratio * 100).toInt() / 100.0}:1" },
        )
    }

    @Test
    fun pairContrast_isSymmetric_soASwappedRoleCannotHide() {
        // The property that makes this test role-blind, asserted rather than assumed. Swap every pair's sides:
        // same number, same verdict. This is what a source-scanning guard can never do.
        for (p in allPairs()) {
            val swapped = contrastRatio(p.background.toArgb(), p.foreground.toArgb())
            assertEquals(
                p.ratio, swapped,
                "contrast must not depend on which side a colour stands: ${p.name}",
            )
        }
    }

    @Test
    fun debugPill_isTheRolesSwappedTwinOfTheCyp337Violations() {
        // Pinned explicitly, because this is the case the source guard cannot see. `WindowBadge` paints a
        // `surface` glyph on an `outline` container; CYP-337 removed `outline` text on `surface`. Same pair.
        for ((scheme, dark) in listOf(MaritimeLight to false, MaritimeDark to true)) {
            val (container, onContainer) = severityContainerFor(Severity.DEBUG, scheme, dark)
            assertEquals(scheme.outline, container, "the DEBUG pill's container is `outline`")
            assertEquals(scheme.surface, onContainer, "the DEBUG pill's glyph is `surface`-coloured")

            val asContainer = contrastRatio(onContainer.toArgb(), container.toArgb())
            val asText = contrastRatio(scheme.outline.toArgb(), scheme.surface.toArgb())
            assertEquals(asText, asContainer, "identical pair, swapped sides — identical number")

            assertTrue(asContainer >= graphicMin, "passes as a graphical object (glyph): $asContainer:1")
            assertTrue(
                asContainer < textMin,
                "and would FAIL as text — which is why colouring a word with it is the CYP-337 violation " +
                    "the source guard catches, while this pill is legitimate: $asContainer:1",
            )
        }
    }
}
