package com.tneff.cyppieagents.i18n

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-357 — **a key that lands in only one locale falls through every net.**
 *
 * `values/strings.xml` (DE) and `values-en/strings.xml` (EN) must carry the SAME key set. Nothing checked that
 * until now: `CommI18nDisclosureTest` only covers the disclosure trio, and Compose Resources happily generates
 * an accessor for a key that exists in the default locale alone — the gap surfaces to the user of the *other*
 * language, at runtime, in production.
 *
 * The occasion was concrete: CYP-333 and CYP-335 added keys to both files at the same time. A mechanically
 * resolved merge conflict loses one key without a single test noticing.
 *
 * **Sets, not counts.** Two files with 471 keys each can carry different keys. The comparison is the symmetric
 * difference, in both directions, and the failure message names the missing keys — a guard that only says
 * "unequal" gets switched off at the first false alarm.
 *
 * **The guard has its own guard.** [bothFilesExistAndAreNonEmpty] fails loudly if a file moves or the parser
 * matches nothing. Without it, a renamed resource directory would make the parity assertion pass on two empty
 * sets — green, and vacuous. Proven, not asserted: point [resolve]'s `base` at a directory that does not exist
 * and **both** tests go red with *„resource not found … A parity check that cannot find its files is worse than
 * none."* That failure mode is not hypothetical; it is the one this codebase spent a day finding elsewhere
 * (CYP-340/CYP-352).
 *
 * **What this test does NOT need to check, because the build already does.** Measured, not assumed: a duplicated
 * key fails `convertXmlValueResourcesForCommonMain` with *„XML file … is not valid. Duplicated key 'x'."*, and
 * malformed XML fails the same task. A test for either could never be made red — it would be decoration. The
 * merge accident this guard exists for is the one the build cannot see: a key present in **one** file only.
 *
 * **Out of scope**, per ticket: translation quality, placeholder consistency (`%1$s`), plurals. Measured today,
 * for the record: placeholders diverge in **0** keys — reported, not repaired.
 */
class I18nKeyParityTest {

    /**
     * Keys that legitimately exist in exactly one locale. **Empty on purpose.** If you add one here, name it and
     * say why in a comment — never widen this into a pattern, or the guard stops guarding.
     */
    private val allowedOnlyInDe = emptySet<String>()
    private val allowedOnlyInEn = emptySet<String>()

    private val de = resolve("values/strings.xml")
    private val en = resolve("values-en/strings.xml")

    /** `<string name="…">` — the only element type these files use (verified). */
    private val keyPattern = Regex("""<string\s+name="([^"]+)"""")

    private fun resolve(relative: String): File {
        val base = "src/commonMain/composeResources"
        // jvmTest runs with the module directory as working dir; walk up as a fallback so the test is not
        // silently unrunnable from a different root.
        var dir = File(".").absoluteFile
        repeat(4) {
            val candidate = File(dir, "$base/$relative")
            if (candidate.isFile) return candidate
            val fromRoot = File(dir, "app/shared/$base/$relative")
            if (fromRoot.isFile) return fromRoot
            dir = dir.parentFile ?: return@repeat
        }
        fail("resource not found: $base/$relative — has the resource directory moved? A parity check that cannot find its files is worse than none.")
    }

    private fun keysOf(file: File): List<String> =
        keyPattern.findAll(file.readText()).map { it.groupValues[1] }.toList()

    /** GUARD, DO NOT DELETE: without this, a moved file or a broken regex makes the parity check vacuously green. */
    @Test
    fun bothFilesExistAndAreNonEmpty() {
        assertTrue(de.isFile, "missing: ${de.path}")
        assertTrue(en.isFile, "missing: ${en.path}")
        assertTrue(keysOf(de).size > 100, "DE parsed to ${keysOf(de).size} keys — the parser matched (almost) nothing")
        assertTrue(keysOf(en).size > 100, "EN parsed to ${keysOf(en).size} keys — the parser matched (almost) nothing")
    }

    @Test
    fun deAndEnCarryTheSameKeySet() {
        val deKeys = keysOf(de).toSet()
        val enKeys = keysOf(en).toSet()

        val onlyInDe = (deKeys - enKeys) - allowedOnlyInDe
        val onlyInEn = (enKeys - deKeys) - allowedOnlyInEn

        if (onlyInDe.isNotEmpty() || onlyInEn.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("DE/EN string keys are out of sync — a user of one language would see a crash or a raw key.")
                    if (onlyInDe.isNotEmpty()) {
                        appendLine("  only in DE (${onlyInDe.size}), missing from values-en/strings.xml:")
                        onlyInDe.sorted().forEach { appendLine("    $it") }
                    }
                    if (onlyInEn.isNotEmpty()) {
                        appendLine("  only in EN (${onlyInEn.size}), missing from values/strings.xml:")
                        onlyInEn.sorted().forEach { appendLine("    $it") }
                    }
                    append("Add the key to the other file, or name it in allowedOnlyInDe/allowedOnlyInEn with a reason.")
                }
            )
        }
    }
}
