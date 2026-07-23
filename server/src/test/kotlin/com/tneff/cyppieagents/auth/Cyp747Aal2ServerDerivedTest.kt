package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * CYP-747 S-AAL2b (B1) — the AAL2 signal is SERVER-DERIVED, never client-supplied. Build-gate source-scan: in the
 * PRODUCTION tree, a `ResolvedIdentity` is constructed with the `aal2 =` argument in exactly ONE place — the Kratos
 * whoami parser ([KratosIdentityProvider]). No `routing/` (or any other) prod code may set `aal2` from a request
 * value; if a future edge did, the chokepoint gate would trust an attacker-controlled assurance level. A new
 * `aal2 =` construction site outside the parser → found ≠ pinned → RED (add it to the gate reasoning or justify it).
 * (Comments stripped so a KDoc mention never false-flags; positive control asserts the known site IS detected.)
 */
class Cyp747Aal2ServerDerivedTest {

    private val mainRoot = repoFile("server/src/main/kotlin")

    private val AAL2_SETTER_FILES = setOf(
        "com/tneff/cyppieagents/auth/KratosIdentityProvider.kt", // the ONLY server-derivation of aal2 (whoami parse)
    )

    @Test
    fun aal2IsSetOnlyInTheKratosParser_neverFromAClientInput() {
        val setter = Regex("""ResolvedIdentity\([^)]*\baal2\s*=""", RegexOption.DOT_MATCHES_ALL)
        val found = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val code = f.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
                    .lines().joinToString("\n") { it.substringBefore("//") }
                setter.containsMatchIn(code)
            }
            .map { it.relativeTo(mainRoot).path.replace('\\', '/') }
            .toSet()
        // POSITIVE CONTROL: the known parser site must be detected (a broken regex → empty → caught here).
        for (f in AAL2_SETTER_FILES) assertTrue(f in found, "positive control FAILED: aal2-setter detector did not flag $f")
        assertEquals(
            AAL2_SETTER_FILES, found,
            "aal2 is set OUTSIDE the Kratos parser — the AAL2 signal must be SERVER-derived, never client-supplied. " +
                "New sites: ${found - AAL2_SETTER_FILES}",
        )
    }

    private fun assertTrue(cond: Boolean, msg: String) { if (!cond) fail(msg) }

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        fail("could not locate '$rel' from ${System.getProperty("user.dir")}")
    }
}
