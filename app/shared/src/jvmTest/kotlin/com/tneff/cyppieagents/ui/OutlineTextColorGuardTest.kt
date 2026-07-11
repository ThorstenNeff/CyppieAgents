package com.tneff.cyppieagents.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-345 — the source guard for the CYP-337 rule: **`outline` is a border colour, never a text colour.**
 *
 * The rule is the consequence of one number, not a style preference. Against `surface`, `colorScheme.outline`
 * measures **3.55:1** (light) / **3.63:1** (dark): *above* WCAG 1.4.11's 3:1 for graphical objects, *below*
 * 1.4.3's 4.5:1 for text. The same colour is correct as a border and wrong as text. Metadata text uses
 * `onSurfaceVariant` (8.69:1 / 9.80:1). The thresholds themselves are pinned by `Cyp337MetadataTextContrastTest`.
 *
 * CYP-337 recoloured the three shipped violations (`NoticeRow`, the comm panel's "· ausstehend", the event-log
 * correlation chip). Nothing stopped them coming back — this does. Same mechanism as [TertiarySourceGuardTest]
 * (CYP-303): scan `commonMain` source, no second machinery.
 *
 * **Why an allowlist and not a cleverer detector.** "Is this `outline` reference a text colour?" cannot be
 * decided by a regex without guessing — `val dot = … outline` looks exactly like `val label = … outline`. A
 * guard that guesses raises false alarms, and a guard that raises false alarms gets switched off. So *every*
 * reference outside `MaritimeTheme.kt` (which defines the token) must appear in [permittedOutlineUses] by
 * name. A new border or divider is a deliberate act that also adds its line here, with a reason.
 *
 * The five entries come from the CYP-337 audit (§3.3); two of them are still under WCAG interpretation — see
 * [permittedOutlineUses]. Two assertions guard both
 * directions: no unlisted reference may appear, and no listed reference may quietly vanish (a stale exemption
 * silently widens the guard's blind spot). The second one also makes the first non-vacuous: if the scan ever
 * pointed at the wrong directory it would find nothing, and the stale check would fail loudly instead of the
 * whole guard passing on an empty file list.
 *
 * ---
 *
 * **This guard carries exactly one clause: "colours no text in human language."** That clause has to be
 * identifier-based — only a human can see whether a character sequence is language, and no number can. The
 * complementary clause ("≥ 3:1, or exempt with a named redundancy carrier") is value-based and lives in
 * [ContrastPairGuardTest], which measures the colour **pair** and is therefore blind to which side a colour
 * stands on. Neither test can do the other's job; together they cover the rule.
 *
 * **What this guard still does NOT cover.**
 *
 * 1. **`outlineVariant` is not protected.** It measures **1.41:1 / 1.52:1** — far below even the 3:1 graphical
 *    threshold. Its two uses (the UNKNOWN status dot, the unselected avatar border) are sound only because
 *    their meaning is carried redundantly, in text and semantics. The allowlist merely records them; the guard
 *    asserts nothing about whether a *new* `outlineVariant` use would be acceptable.
 * 2. **`commonMain` only**, like its sibling. Every Compose colour in this app lives there — `wasmJsMain` and
 *    `jsMain` contain no `colorScheme` reference at all (checked, not assumed) — but a colour introduced in a
 *    platform source set would slip past. Widen the scan when that day comes.
 * 3. **No indirection.** `val c = colorScheme.outline` in one file and `Text(color = c)` in another passes. It
 *    catches the mistake people actually make, not an adversary.
 *
 * (The former limit #1 — "blind to `outline` as a **container** behind text" — is **gone**: CYP-359's pair test
 * measures that case, and `WindowBadge`'s DEBUG pill is pinned there by name. A limit that has been lifted and
 * left standing is the next lie.)
 *
 * Mutation proof (run, not assumed): each of the three CYP-337 sites recoloured back to `outline` → RED, naming
 * file, line, the offending source and the rule. Removing a certified use without its allowlist entry → RED on
 * the second assertion.
 */
class OutlineTextColorGuardTest {

    /** Matches `colorScheme.outline`, `colorScheme.outlineVariant`, and the `scheme.outline…` severity source. */
    private val outlineReference = Regex("""\b(colorScheme|scheme)\.outline\w*""")

    /**
     * The `outline`/`outlineVariant` uses this guard permits, each with the reason it is permitted. NOT named
     * "certified decorative" — because two of them are neither certified nor decorative (see below), and a name
     * that overstates what it holds is the same defect this guard exists to prevent. Keyed by file
     * name + the exact source line (trimmed), so a line moving up or down does not rot the entry — but changing
     * what the line *does* invalidates it.
     *
     * **Not all of these are "decorative", and saying so was wrong.** The two DEBUG entries feed real rendering
     * paths: `severityColorFor(DEBUG)` is consumed as a **text colour** in `ProductLeadPanel` (the severity
     * glyph), and `severityContainerFor(DEBUG)` paints an `outline` **container** under `surface` text in
     * `WindowBadge`.
     *
     * **Decided (UIUX-Designer2, audit `085e02a`): both are graphical objects, WCAG 1.4.11, 3:1 — both pass.**
     * Not by the rule-of-thumb "glyph, not prose", but by the norm: WCAG defines text as a *"sequence of
     * characters expressing something in human language"*. A `·` is an icon that happens to come from a font; the
     * norm attaches to the character sequence, not to the `Text` composable.
     *
     * **They pass on the NUMBER (3.55:1 ≥ 3:1), not on redundancy** — and that distinction is load-bearing. The
     * assumption that a visible severity label sits beside each glyph is false: in `ProductLeadPanel` the label is
     * only a `contentDescription`, and the `WindowBadge` pill renders the glyph alone (verified in source). The
     * DEBUG *rail* has that redundancy; the DEBUG *glyph* does not. Anyone who dims these further, reasoning "it's
     * redundant anyway", breaks them. A correct exemption with a wrong reason is a trap with an expiry date.
     *
     * The same colour from the same source WOULD violate 1.4.3 the moment it coloured a **word**:
     * `Text(severityLabel(sev), color = severityColor(sev))` — "Debug" in `outline` — owes 4.5:1 and fails. This
     * guard would catch that, because there `outline` is the foreground.
     */
    private val permittedOutlineUses: Map<String, Map<String, String>> = mapOf(
        "EventVisuals.kt" to mapOf(
            "Severity.DEBUG -> scheme.outline" to
                "DECIDED (UIUX-Designer2, audit 085e02a): the DEBUG severity GLYPH `·` in ProductLeadPanel. A " +
                    "graphical object under WCAG 1.4.11 (3:1) — the norm attaches to the character sequence, not " +
                    "to the `Text` composable. It passes on the NUMBER (3.55:1 / 3.63:1 ≥ 3:1), NOT on redundancy: " +
                    "the severity label beside it is a contentDescription only, never rendered. Do not dim it " +
                    "further on the assumption that a visible label backs it up.",
            "Severity.DEBUG -> scheme.outline to scheme.surface" to
                "DECIDED (UIUX-Designer2, audit 085e02a): the WindowBadge DEBUG pill — `outline` is the CONTAINER, " +
                    "`surface` the glyph on it. Graphical object, 1.4.11, 3:1, passes on the NUMBER. The pill " +
                    "renders the glyph ALONE (no visible label), so redundancy is not what saves it. This is the " +
                    "roles-swapped case: invisible to THIS guard, but measured by ContrastPairGuardTest (CYP-359), " +
                    "which asserts the pair and would go red the day it stops passing.",
        ),
        "AgentWindow.kt" to mapOf(
            "AgentLifecycleState.STOPPED -> MaterialTheme.colorScheme.outline" to
                "8dp status DOT; the state is spelled out beside it (StatusIndicator: colour is never the sole signal)",
            "AgentLifecycleState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant" to
                "8dp status DOT for the quietest state; `outlineVariant` is 1.41:1 / 1.52:1 — sound ONLY because " +
                    "the state is spelled out beside it and the row carries a11y_agent_status. Decorative, redundant.",
            "val railColor = MaterialTheme.colorScheme.outlineVariant" to
                "CYP-381 §7.1 receded-history gutter RAIL — a 2dp vertical RULE (drawBehind, decorative, " +
                    "clearAndSetSemantics) marking the forgotten-history block; a border/divider role, never text " +
                    "(the receded rows keep their own AA text colours — role-demotion, not alpha). Spec-mandated.",
        ),
        "AgentAvatarSection.kt" to mapOf(
            "BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)," to
                "1dp BORDER of an unselected preset; selection is carried by primary + 3dp + the `selected` semantics flag",
        ),
        "ThinVerticalScrollbar.kt" to mapOf(
            "restColor = MaterialTheme.colorScheme.outline," to
                "CYP-392: the RESTING scrollbar thumb — a graphical object (WCAG 1.4.11, 3:1), never text. Passes on " +
                    "the NUMBER (outline↔surface 3.55:1 / 3.63:1 ≥ 3:1), pinned by Cyp392ScrollbarStyleTest. NO alpha " +
                    "(outline sits near the 3:1 floor — the CYP-337 lesson); the active/hover colour is onSurfaceVariant.",
        ),
    )

    private data class Hit(val file: String, val line: Int, val text: String)

    private fun scan(): List<Hit> {
        val root = locateCommonMain()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "MaritimeTheme.kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, raw) ->
                    val code = raw.substringBefore("//").trim()
                    // Skip KDoc / block-comment bodies — a comment naming the rule is not a use of it.
                    if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) return@mapNotNull null
                    if (!outlineReference.containsMatchIn(code)) return@mapNotNull null
                    Hit(file.name, i + 1, code)
                }
            }
            .toList()
    }

    @Test
    fun noOutlineReferenceInCommonMain_outsideTheCertifiedDecorativeUses() {
        val offenders = scan().filter { hit -> permittedOutlineUses[hit.file]?.containsKey(hit.text) != true }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("CYP-337 rule: `outline` is a BORDER colour, never a TEXT colour.")
                appendLine("  outline on surface          = 3.55:1 (light) / 3.63:1 (dark)")
                appendLine("  WCAG 1.4.11 graphical object = 3:1   -> PASS  (borders, dividers, status dots)")
                appendLine("  WCAG 1.4.3  text             = 4.5:1 -> FAIL  (any Text(color = …))")
                appendLine("Metadata text uses `onSurfaceVariant` (8.69:1 / 9.80:1).")
                appendLine()
                appendLine("These references are not in this test's permitted-uses allowlist.")
                appendLine("If a use really is a border/divider/dot, add its exact line to `permittedOutlineUses`")
                appendLine("with the reason. If it colours text, use `onSurfaceVariant` instead.")
                appendLine("This guard carries ONE clause — `outline` colours no text in human language. The")
                appendLine("contrast of the colour PAIR (either role) is measured by ContrastPairGuardTest.")
                appendLine()
                offenders.forEach { appendLine("  ${it.file}:${it.line}  ${it.text}") }
            },
        )
    }

    @Test
    fun everyCertifiedException_isStillPresent_soTheAllowlistCannotRot() {
        // An exemption for a line that no longer exists is a hole nobody sees: it silently permits a future line
        // with the same text. Delete the entry when you delete the use.
        val found = scan().groupBy({ it.file }, { it.text }).mapValues { it.value.toSet() }
        val stale = permittedOutlineUses.flatMap { (file, entries) ->
            entries.keys.filter { line -> found[file]?.contains(line) != true }.map { "$file: $it" }
        }
        assertTrue(
            stale.isEmpty(),
            "These allowlist entries no longer match any source line — the use was changed or removed, so the " +
                "exemption must go too (a stale entry pre-approves a future line with the same text):\n" +
                stale.joinToString("\n") { "  $it" },
        )
    }

    /** Walk up to the module's commonMain kotlin root (mirrors [TertiarySourceGuardTest]'s locate pattern). */
    private fun locateCommonMain(): File {
        val rel = "src/commonMain/kotlin/com/tneff/cyppieagents"
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isDirectory) return it }
            File(cur, "app/shared/$rel").let { if (it.isDirectory) return it }
            cur = cur.parentFile
        }
        error("Could not locate $rel from ${File(".").absolutePath}")
    }
}
