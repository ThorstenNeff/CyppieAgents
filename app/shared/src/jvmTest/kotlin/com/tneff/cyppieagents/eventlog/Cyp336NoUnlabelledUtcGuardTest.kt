package com.tneff.cyppieagents.eventlog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-336 AC6 — **no caller renders an unlabelled UTC timestamp again.**
 *
 * `formatHhMmSsMillis(tsMs, offsetMs)` is the *pure* formatter: hand it `0L` and it renders UTC, silently and
 * convincingly. That is precisely how `formatTs` shipped — correct code, correct KDoc, and every caller reading
 * it as local time. The frame of reference must live in the **name** a caller types, so production code may only
 * type [formatLocalHhMmSsMillis].
 *
 * Same mechanism as `OutlineTextColorGuardTest` / `TertiarySourceGuardTest`: scan `commonMain`, name the one
 * legitimate use, fail on any other. A new deliberate use of the pure formatter (say, an explicitly **labelled**
 * UTC display for the multi-host case, doc 06 §6) is a conscious act that adds its line here with a reason.
 *
 * **Limits, named rather than assumed.**
 *
 * 1. It reads identifiers, so it cannot see through an alias (`val f = ::formatHhMmSsMillis`), and it scans
 *    `commonMain` only — every timestamp rendering in this app lives there (checked). It catches the mistake
 *    people make, not an adversary.
 * 2. **Gradle does not track the scanned `.kt` files as inputs of this test task.** An edit that leaves the
 *    compiled classes byte-identical — a named argument, a reordered import — can leave this test `FROM-CACHE`,
 *    i.e. *not run at all*. Measured, not assumed: changing the permitted call site to
 *    `clock.utcOffsetMs(atEpochMs = tsMs)` produced a green, cached result; with `--rerun-tasks` both assertions
 *    went red. Introducing a *violation* always changes bytecode and therefore does re-run the task, so the
 *    guard's main direction holds — but the stale-permit direction can be missed. The same hole exists in
 *    `TertiarySourceGuardTest` and `OutlineTextColorGuardTest`; it is the CYP-342 disease one level over
 *    (a check whose real input the build cannot see). Fixing it means declaring the scanned directory as a task
 *    input, which belongs in its own ticket because it changes the build for all three guards.
 *
 * The *value* side — that the rendering really follows the operator's zone — is measured by
 * `Cyp336LocalTimestampTest`, which goes red on a UTC fallback in **any** timezone, including UTC itself.
 */
class Cyp336NoUnlabelledUtcGuardTest {

    /** The pure, offset-taking formatter. Every call must justify its offset argument. */
    private val pureFormatter = Regex("""\bformatHhMmSsMillis\s*\(""")

    /**
     * The only place in `commonMain` allowed to call the pure formatter: the local wrapper itself, which obtains
     * the offset from the per-instant [com.tneff.cyppieagents.agentview.TranscriptClock] seam.
     */
    private val permittedCallSites: Map<String, Set<String>> = mapOf(
        "EventVisuals.kt" to setOf("formatHhMmSsMillis(tsMs, clock.utcOffsetMs(tsMs))"),
    )

    private data class Hit(val file: String, val line: Int, val text: String)

    @Test
    fun onlyTheLocalWrapperCallsThePureFormatter() {
        val offenders = scan().filter { hit -> permittedCallSites[hit.file]?.contains(hit.text) != true }
        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("CYP-336: a timestamp's frame of reference belongs in the NAME a caller types.")
                appendLine("`formatHhMmSsMillis(ts, 0L)` renders UTC and looks exactly like local time on screen —")
                appendLine("that is how `formatTs` shipped a two-hour lie to every operator east of Greenwich.")
                appendLine()
                appendLine("Production code calls `formatLocalHhMmSsMillis(ts)`, which resolves the offset per")
                appendLine("instant through the TranscriptClock seam. If you really need a raw offset (a LABELLED")
                appendLine("UTC display for the multi-host case, doc 06 §6), add the line to `permittedCallSites`.")
                appendLine()
                offenders.forEach { appendLine("  ${it.file}:${it.line}  ${it.text}") }
            },
        )
    }

    @Test
    fun everyPermittedCallSite_stillExists_soTheAllowlistCannotRot() {
        // A permit for a line that no longer exists pre-approves a future line with the same text — and it also
        // means the scan found nothing, which would make the assertion above vacuously green.
        val found = scan().groupBy({ it.file }, { it.text }).mapValues { it.value.toSet() }
        val stale = permittedCallSites.flatMap { (file, lines) ->
            lines.filter { found[file]?.contains(it) != true }.map { "$file: $it" }
        }
        assertTrue(
            stale.isEmpty(),
            "These permitted call sites no longer match any source line — delete the permit with the use:\n" +
                stale.joinToString("\n") { "  $it" },
        )
    }

    private fun scan(): List<Hit> {
        val root = locateCommonMain()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().mapNotNull { (i, raw) ->
                    val code = raw.substringBefore("//").trim()
                    if (code.isEmpty() || code.startsWith("*") || code.startsWith("/*")) return@mapNotNull null
                    // The declaration itself is not a call.
                    if (code.startsWith("fun formatHhMmSsMillis")) return@mapNotNull null
                    if (!pureFormatter.containsMatchIn(code)) return@mapNotNull null
                    Hit(file.name, i + 1, code)
                }
            }
            .toList()
    }

    /** Walk up to the module's commonMain kotlin root (mirrors the sibling guards' locate pattern). */
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
