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
 * **What this guard does NOT cover. Read this before trusting it.**
 *
 * 1. **Foreground only.** It asks "does `outline` appear here", and the rule it defends is about `outline` as a
 *    *text* colour. It is structurally blind to `outline` as a **container** behind text: `WindowBadge`'s DEBUG
 *    pill paints `surface`-coloured text ON an `outline` container — the same 3.55:1 pair, roles swapped. No
 *    rule phrased as "`outline` must not be a text colour" can find that. Naming the limit here so the next
 *    reader does not mistake this guard for a complete contrast check.
 * 2. **`outlineVariant` is not protected.** It measures **1.41:1 / 1.52:1** — far below even the 3:1 graphical
 *    threshold. Its two uses (the UNKNOWN status dot, the unselected avatar border) are sound only because
 *    their meaning is carried redundantly, in text and semantics. The allowlist merely records them; the guard
 *    asserts nothing about whether a *new* `outlineVariant` use would be acceptable.
 * 3. **`commonMain` only**, like its sibling. Every Compose colour in this app lives there — `wasmJsMain` and
 *    `jsMain` contain no `colorScheme` reference at all (checked, not assumed) — but a colour introduced in a
 *    platform source set would slip past. Widen the scan when that day comes.
 * 4. **No indirection.** `val c = colorScheme.outline` in one file and `Text(color = c)` in another passes. It
 *    catches the mistake people actually make, not an adversary.
 *
 * The guard is not wrong. It is **narrower than its name promises**, and that gap is where the next defect will
 * live.
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
     * `WindowBadge`. Whether a meaning-bearing glyph owes 4.5:1 (WCAG 1.4.3, text) or 3:1 (1.4.11, non-text) is a
     * genuine interpretation, and it is **open** — UIUX-Designer2 is deciding it. Until then these two are listed
     * as *pending*, not as certified. An exemption whose reason is a guess is an exemption that rots.
     */
    private val permittedOutlineUses: Map<String, Map<String, String>> = mapOf(
        "EventVisuals.kt" to mapOf(
            "Severity.DEBUG -> scheme.outline" to
                "PENDING WCAG interpretation (UIUX-Designer2): consumed as a TEXT colour for the DEBUG severity " +
                    "glyph in ProductLeadPanel. Passes 1.4.11 (3:1) as a symbol, fails 1.4.3 (4.5:1) as text. Its " +
                    "meaning is announced via contentDescription + label, never colour — which is the argument for " +
                    "reading it as a symbol. Do not treat this line as settled.",
            "Severity.DEBUG -> scheme.outline to scheme.surface" to
                "PENDING WCAG interpretation (UIUX-Designer2): `outline` is the CONTAINER here, `surface` the text " +
                    "on it (WindowBadge DEBUG pill) — the same 3.55:1 pair with the roles swapped, which this " +
                    "guard cannot detect by construction (see the class KDoc, limit 1).",
        ),
        "AgentWindow.kt" to mapOf(
            "AgentLifecycleState.STOPPED -> MaterialTheme.colorScheme.outline" to
                "8dp status DOT; the state is spelled out beside it (StatusIndicator: colour is never the sole signal)",
            "AgentLifecycleState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant" to
                "8dp status DOT for the quietest state; `outlineVariant` is 1.41:1 / 1.52:1 — sound ONLY because " +
                    "the state is spelled out beside it and the row carries a11y_agent_status. Decorative, redundant.",
        ),
        "AgentAvatarSection.kt" to mapOf(
            "BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)," to
                "1dp BORDER of an unselected preset; selection is carried by primary + 3dp + the `selected` semantics flag",
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
                appendLine("These references are not in the certified-decorative allowlist of this test.")
                appendLine("If a use really is a border/divider/dot, add its exact line to `permittedOutlineUses`")
                appendLine("with a reason. If it colours text, use `onSurfaceVariant` instead.")
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
