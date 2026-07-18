package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CYP-708 — pins the `.deb` PROD and TEST maintainer scripts (and their systemd units) as **twins**: after the
 * intended `-test` scoping is normalised away, their EXECUTABLE content must be byte-identical. Any other
 * divergence is red.
 *
 * ## Why this guard is worth its weight
 *
 * `cyppiehub-test` exists to exercise the install → remove → reinstall → purge lifecycle on a real host without
 * touching live data. That validation is only meaningful while the twin actually MIRRORS prod. The moment the two
 * drift, the acceptance run is blind in exactly the region that drifted — it happily proves a lifecycle nobody
 * ships. Two real bugs already lived in that blind spot (cross-user `mktemp`, home-directory visibility).
 *
 * ## What is allowed to differ, and nothing else
 *
 *  1. **The `-test` scoping itself** — user, paths, ports, unit name. Normalised away by [scopeTestToProd].
 *  2. **Comments.** Prose explains a file, it does not run. Stripped before comparison so the two may document
 *     themselves differently (they do: CYP-635 vs CYP-637 headers).
 *  3. **Two human-readable labels**, allowlisted line-wise and narrowly ([ALLOWED_LABEL_DIFFS]): the unit's
 *     `Description=` and the `useradd -c` comment. A test instance SHOULD announce itself in `systemctl status`
 *     and in `/etc/passwd`; forcing those to match would be pinning the wrong thing.
 *
 * Everything else — an option, a mode, an order, a `chown`, an added or missing line — fails.
 *
 * ## Non-vacuity (the part that makes a green mean something)
 *
 * A normaliser that over-reaches would erase the differences it is meant to police and this guard would pass
 * forever while measuring nothing. Three defences, all asserted here:
 *  - [twinNormalisation_isNotDegenerate] — the raw files DO differ, the normalised ones do NOT. If normalisation
 *    ever became a no-op (nothing to compare) or total (everything erased), one of the two halves breaks.
 *  - [twinComparator_detectsAnInjectedDivergence] — the comparator is run against a deliberately mutated copy of
 *    prod and MUST report a difference. This is the discrimination arm carried inside the test: the guard proves
 *    it can fail, on every run, without anyone remembering to mutation-test it by hand.
 *  - Each pair asserts a non-empty normalised body, so an unreadable/emptied file cannot pass as "identical".
 */
class DebTwinPinTest {

    /** prod path to test path. Both are `test` inputs of this module, so editing either re-runs this guard. */
    private val twins = listOf(
        "deploy/linux/deb-resources/postinst" to "deploy/linux/deb-resources-test/postinst",
        "deploy/linux/deb-resources/prerm" to "deploy/linux/deb-resources-test/prerm",
        "deploy/linux/deb-resources/postrm" to "deploy/linux/deb-resources-test/postrm",
        "deploy/linux/cyppiehub.service" to "deploy/linux/cyppiehub-test.service",
    )

    /**
     * Reverse the `-test` scoping so the twin can be compared against prod. Order matters: `cyppiehub-test` must be
     * rewritten before `cyppie-test`, otherwise the longer token is destroyed by the shorter rule and paths like
     * `/var/lib/cyppiehub-test` would normalise to `/var/lib/cyppiehubtest`.
     */
    private fun scopeTestToProd(line: String): String =
        line.replace("cyppiehub-test", "cyppiehub")
            .replace("cyppie-test", "cyppie")
            .replace("18787", "8787")
            .replace("18786", "8786")

    /**
     * Executable content only. Three things carry no behaviour and are therefore allowed to differ:
     *  - whole-line and TRAILING comments,
     *  - blank lines,
     *  - the WIDTH of whitespace runs. The test twin aligns some columns differently purely because `cyppie-test` is
     *    six characters longer than `cyppie` (`install -d -o root  -g root` vs `-o root       -g root`). Pinning
     *    column alignment would report cosmetic drift as a defect — the first version of this guard did exactly that.
     * A leading-indentation change is likewise cosmetic in shell, so lines are trimmed and their internal runs collapsed.
     */
    private fun executableLines(f: File): List<String> =
        f.readLines()
            .map { stripTrailingComment(it) }
            .map { it.trim().replace(Regex("[ \t]+"), " ") }
            .filter { it.isNotBlank() }

    /**
     * Drop a trailing `#` comment, but only when the `#` is OUTSIDE quotes — these scripts contain quoted text and a
     * naive `substringBefore("#")` would silently truncate a real command. Scans the line tracking quote state.
     */
    private fun stripTrailingComment(line: String): String {
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' -> i++ // escaped: the next character is literal, never a quote or a comment sigil
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '#' && !inSingle && !inDouble && (i == 0 || line[i - 1].isWhitespace()) -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** Line prefixes whose text is a human label, deliberately different in the test twin. Kept tiny on purpose. */
    private val ALLOWED_LABEL_DIFFS = listOf("Description=", "useradd ")

    private fun isAllowedLabelDiff(prod: String, test: String): Boolean =
        ALLOWED_LABEL_DIFFS.any { p -> prod.trimStart().startsWith(p) && test.trimStart().startsWith(p) }

    /**
     * The comparator, factored out so [twinComparator_detectsAnInjectedDivergence] can point it at a known-bad input.
     * Returns a human-readable description of every disallowed divergence, or an empty list when the two are twins.
     */
    private fun divergences(prodLines: List<String>, testLinesNormalised: List<String>): List<String> {
        val out = mutableListOf<String>()
        val max = maxOf(prodLines.size, testLinesNormalised.size)
        for (i in 0 until max) {
            val p = prodLines.getOrNull(i)
            val t = testLinesNormalised.getOrNull(i)
            when {
                p == null -> out += "line ${i + 1}: TEST has an extra line not in prod: `$t`"
                t == null -> out += "line ${i + 1}: TEST is MISSING the prod line: `$p`"
                p == t -> Unit
                isAllowedLabelDiff(p, t) -> Unit // an allowlisted human label
                else -> out += "line ${i + 1}:\n     prod: `$p`\n     test: `$t`"
            }
        }
        return out
    }

    @Test
    fun debTwins_differ_onlyByTheIntendedTestScoping() {
        for ((prodRel, testRel) in twins) {
            val prodLines = executableLines(repoFile(prodRel))
            val testLines = executableLines(repoFile(testRel)).map(::scopeTestToProd)

            assertTrue(
                prodLines.isNotEmpty() && testLines.isNotEmpty(),
                "$prodRel / $testRel produced no executable lines — an empty or unreadable file must never read as " +
                    "'identical'.",
            )

            val diffs = divergences(prodLines, testLines)
            assertTrue(
                diffs.isEmpty(),
                "TWIN DRIFT — `$testRel` diverges from `$prodRel` beyond the intended `-test` scoping:\n" +
                    diffs.joinToString("\n") { "  - $it" } +
                    "\n\nThe -test package exists to prove the .deb lifecycle on a real host. Every line that drifts " +
                    "is a line the acceptance run no longer validates: it exercises a lifecycle we do not ship. Fix " +
                    "the twin, or — if the difference is genuinely intended — widen ALLOWED_LABEL_DIFFS deliberately " +
                    "and say why.",
            )
        }
    }

    /**
     * Non-vacuity, both directions. Before normalisation the twins must genuinely differ (otherwise the scoping this
     * guard polices does not exist and the comparison is empty); after it they must not.
     */
    @Test
    fun twinNormalisation_isNotDegenerate() {
        for ((prodRel, testRel) in twins) {
            val prodRaw = executableLines(repoFile(prodRel))
            val testRaw = executableLines(repoFile(testRel))
            assertNotEquals(
                prodRaw, testRaw,
                "$testRel is already byte-identical to $prodRel BEFORE normalisation — then the `-test` scoping is " +
                    "missing entirely (the twin would collide with the live hub), and this guard is comparing nothing.",
            )
            assertEquals(
                emptyList(), divergences(prodRaw, testRaw.map(::scopeTestToProd)),
                "$testRel does not normalise onto $prodRel (see the twin-drift test for the detail).",
            )
        }
    }

    /**
     * The discrimination arm, run on every build: feed the comparator a copy of prod with ONE line changed and assert
     * it complains. A guard that cannot be shown to fail is worth nothing, and hand-run mutation testing is exactly
     * the step that gets skipped. Uses a `chmod 0600` → `chmod 0644` flip: a real, security-relevant drift of the kind
     * this guard exists to catch.
     */
    @Test
    fun twinComparator_detectsAnInjectedDivergence() {
        val prodLines = executableLines(repoFile("deploy/linux/deb-resources/postinst"))
        val victim = prodLines.indexOfFirst { it.contains("chmod 0600") }
        assertTrue(
            victim >= 0,
            "expected a `chmod 0600` line in the prod postinst to mutate — if it moved, retarget this probe rather " +
                "than deleting it, or the comparator loses its proof of teeth.",
        )
        val mutated = prodLines.toMutableList().also { it[victim] = it[victim].replace("chmod 0600", "chmod 0644") }

        val found = divergences(prodLines, mutated)
        assertTrue(
            found.isNotEmpty(),
            "the twin comparator did NOT flag an injected `chmod 0600` → `0644` divergence. It cannot go red, so " +
                "every green it has ever reported is vacuous.",
        )
        assertTrue(
            found.any { it.contains("0644") },
            "the comparator reported a divergence but not the injected one — it is detecting something else. Found: $found",
        )
    }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("could not locate `$rel` from ${System.getProperty("user.dir")} upwards")
    }
}
