package com.tneff.cyppieagents.comm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CYP-53 §1 disclosure separation, guarded **per locale**. The proactive read-only state
 * (`comm_readonly_hint`), the ACL-denied send attempt (`comm_send_denied`) and a generic send failure
 * (`comm_send_failed`) must stay three distinct texts in EVERY language — never collapsed onto one
 * wording (which would re-blur "darf nicht" vs "ging schief").
 *
 * A Compose `stringResource` check would only see the JVM default locale (locale-blind), so a DE-only
 * collapse — exactly the state CYP-53 fixes in the primary language — would slip through. This parses
 * BOTH `strings.xml` files directly and asserts distinctness in each, so a DE-only merge turns it RED.
 */
class CommI18nDisclosureTest {

    private val disclosureKeys = listOf("comm_readonly_hint", "comm_send_denied", "comm_send_failed")

    @Test
    fun disclosureTrio_stayDistinct_inEveryLocale() {
        for (valuesDir in listOf("values", "values-en")) {
            val strings = parseStrings(locateStrings(valuesDir))
            val texts = disclosureKeys.map {
                strings[it] ?: error("$valuesDir/strings.xml is missing the key '$it' (DE+EN parity required)")
            }
            assertTrue(texts.all { it.isNotBlank() }, "$valuesDir: disclosure texts must be non-blank — $texts")
            assertEquals(
                texts.size, texts.toSet().size,
                "$valuesDir: comm_readonly_hint / comm_send_denied / comm_send_failed must stay distinct " +
                    "(read-only state ≠ denied attempt ≠ generic failure) — got $texts",
            )
        }
    }

    /** Walk up from the test working dir to find the module's composeResources XML for [valuesDir]. */
    private fun locateStrings(valuesDir: String): File {
        val rel = "src/commonMain/composeResources/$valuesDir/strings.xml"
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.exists()) return it }
            File(cur, "app/shared/$rel").let { if (it.exists()) return it }
            cur = cur.parentFile
        }
        error("Could not locate $rel from ${File(".").absolutePath}")
    }

    private fun parseStrings(file: File): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
}
