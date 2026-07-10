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
 * reference outside `MaritimeTheme.kt` (which defines the token) must appear in [certifiedDecorativeUses] by
 * name. A new border or divider is a deliberate act that also adds its line here, with a reason.
 *
 * The five entries are the ones the CYP-337 audit (§3.3) certified as correct. Two assertions guard both
 * directions: no unlisted reference may appear, and no listed reference may quietly vanish (a stale exemption
 * silently widens the guard's blind spot). The second one also makes the first non-vacuous: if the scan ever
 * pointed at the wrong directory it would find nothing, and the stale check would fail loudly instead of the
 * whole guard passing on an empty file list.
 *
 * **Scope, honestly:** `commonMain` only, like its sibling. Every Compose colour in this app lives there —
 * `wasmJsMain` contains no `colorScheme` reference at all (checked) — but a UI colour introduced in a platform
 * source set would slip past. Widen the scan when that day comes; do not assume it is covered.
 *
 * It also cannot see through an indirection: `val c = colorScheme.outline` in one file, `Text(color = c)` in
 * another, would pass. It catches the mistake people actually make, not an adversary.
 *
 * Mutation proof (run, not assumed): each of the three CYP-337 sites recoloured back to `outline` → RED, naming
 * file, line, the offending source and the rule. Removing a certified use without its allowlist entry → RED on
 * the second assertion.
 */
class OutlineTextColorGuardTest {

    /** Matches `colorScheme.outline`, `colorScheme.outlineVariant`, and the `scheme.outline…` severity source. */
    private val outlineReference = Regex("""\b(colorScheme|scheme)\.outline\w*""")

    /**
     * The complete set of `outline`/`outlineVariant` uses certified **decorative** by the CYP-337 audit (§3.3).
     * Keyed by file name + the exact source line (trimmed), so a line moving up or down does not rot the entry
     * — but changing what the line *does* invalidates it.
     */
    private val certifiedDecorativeUses: Map<String, Map<String, String>> = mapOf(
        "EventVisuals.kt" to mapOf(
            "Severity.DEBUG -> scheme.outline" to
                "DEBUG severity GLYPH; its meaning rides the contentDescription + label, never colour (1.4.11, ≥3:1)",
            "Severity.DEBUG -> scheme.outline to scheme.surface" to
                "DEBUG severity PILL container; the `·` glyph's meaning is announced, not coloured",
        ),
        "AgentWindow.kt" to mapOf(
            "AgentLifecycleState.STOPPED -> MaterialTheme.colorScheme.outline" to
                "8dp status DOT; the state is spelled out beside it (StatusIndicator: colour is never the sole signal)",
            "AgentLifecycleState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant" to
                "8dp status DOT for the quietest state; decorative, meaning in the adjacent text + a11y_agent_status",
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
        val offenders = scan().filter { hit -> certifiedDecorativeUses[hit.file]?.containsKey(hit.text) != true }

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
                appendLine("If a use really is a border/divider/dot, add its exact line to `certifiedDecorativeUses`")
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
        val stale = certifiedDecorativeUses.flatMap { (file, entries) ->
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
