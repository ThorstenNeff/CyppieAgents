package com.tneff.cyppieagents.auth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-829 (PL binding addendum, must land before Multi-Hub off-loopback arming) — the **getenv-compare closure** for
 * the static god-token. CYP-828 confined the in-memory value-compare (`X == *.operatorToken`) to the operatorEligible
 * path; this closes the remaining terminal axis: a `X == System.getenv("OPERATOR_TOKEN")` — reading the secret STRAIGHT
 * from the environment and comparing it to a presented token — would grant operator while bypassing operatorEligible,
 * `isOperator`, AND the private in-memory holders entirely.
 *
 * The reference to the secret env key can ONLY happen through its exact literal `"OPERATOR_TOKEN"` (distinct from the
 * kill-switch `"CYPPIE_OPERATOR_TOKEN_DISABLED"` and the CP token `"CYPPIE_CP_OPERATOR_TOKEN"` — the surrounding quotes
 * anchor it). It is legitimately READ at exactly two SEED sites (each assigns it into a [com.tneff.cyppieagents.boot.
 * Secrets] / [com.tneff.cyppieagents.routing.CommConfig] that then builds a `TokenRegistry` via the Layer-B narrow
 * consumer — a read, never a grant-compare). The lint pins that confinement: a getenv-compare bypass introduces the
 * literal in a THIRD file → this reds (the CYP-811 source-confinement pattern).
 */
class Cyp829OperatorTokenGetenvConfinementTest {

    /** The two legitimate SEED sites that read `OPERATOR_TOKEN` from the environment (grows ONLY by deliberate review). */
    private val OPERATOR_TOKEN_ENV_SEED_FILES = setOf(
        "com/tneff/cyppieagents/boot/Secrets.kt",       // fromEnv: the production boot seed
        "com/tneff/cyppieagents/routing/CommRoutes.kt", // CommConfig.dev(): the dev/test seed (loopback posture)
    )

    @Test
    fun operatorTokenEnvKey_confinedToSeedSites_noGetenvCompareBypass() {
        val literal = Regex("\"OPERATOR_TOKEN\"") // EXACT env key; the quotes exclude CYPPIE_OPERATOR_TOKEN_DISABLED etc.
        val found = serverMain().filter { f ->
            f.readLines().any { l ->
                val t = l.trimStart()
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@any false // skip comments
                literal.containsMatchIn(l.substringBefore("//"))
            }
        }.map { it.relativeTo(serverMainRoot()).path }.toSet()

        // positive control: the detector actually flags the known seed sites (a broken detector must go RED, not green).
        for (f in OPERATOR_TOKEN_ENV_SEED_FILES) {
            assertTrue(f in found, "positive control FAILED: the OPERATOR_TOKEN env-literal detector did not flag known seed site [$f] — detector broken")
        }
        assertEquals(
            OPERATOR_TOKEN_ENV_SEED_FILES, found,
            "the static god-token env key \"OPERATOR_TOKEN\" drifted from the seed sites. A NEW reference is a getenv-" +
                "compare bypass (X == getenv(\"OPERATOR_TOKEN\") grants operator OUTSIDE operatorEligible). NEW: " +
                "${found - OPERATOR_TOKEN_ENV_SEED_FILES}; STALE: ${OPERATOR_TOKEN_ENV_SEED_FILES - found}",
        )
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir ?: fail("repo root (settings.gradle.kts) not found from ${System.getProperty("user.dir")}")
    }
    private fun serverMainRoot(): File = File(repoRoot(), "server/src/main/kotlin")
    private fun serverMain(): List<File> =
        serverMainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
