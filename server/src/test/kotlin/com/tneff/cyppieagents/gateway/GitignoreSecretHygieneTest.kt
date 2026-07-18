package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-681 — pins the root `.gitignore` secret-hygiene backstop: private-key / certificate / keystore patterns must
 * stay ignored so an accidental secret can never land in git history. The patterns are already present (this closes the
 * gap that NOTHING guarded them — a future `.gitignore` edit could silently remove them without any test reddening).
 *
 * ★ Asserts each pattern as an actual `.gitignore` DIRECTIVE line (`trim() == pattern`), not a substring — the
 * rationale comment above the block also names these extensions, so a `contains` check would be false-green if a
 * pattern line were removed but the comment left. `.gitignore` is a declared `test` input (CC2) so an edit re-runs
 * this guard. Mutation: remove any pattern line → red.
 */
class GitignoreSecretHygieneTest {

    /** Private-key / certificate / keystore extensions that must never be committable (CYP-681 + the pre-existing block). */
    private val secretPatterns = listOf("*.pem", "*.key", "*.p12", "*.pfx", "*.keystore", "*.jks")

    @Test
    fun rootGitignore_ignoresPrivateKeyCertAndKeystorePatterns() {
        val lines = repoFile(".gitignore").readLines().map { it.trim() }
        for (p in secretPatterns) {
            assertTrue(
                p in lines,
                "the root .gitignore must ignore '$p' as an actual pattern line (not just a comment) — the backstop " +
                    "that keeps a private key / certificate / keystore from ever landing in git history.",
            )
        }
    }

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
