package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-680 — pins that the Linux `.deb` systemd units disable OS core dumps (`LimitCORE=0`). The hub holds the DECRYPTED
 * master key + tokens in RAM; an OS core dump would write them to disk. This is the OS-level complement to the in-JVM
 * dump-off flags the jpackage launcher already carries (`-XX:-HeapDumpOnOutOfMemoryError` / `-XX:-CreateCoredumpOnCrash`,
 * single-sourced via CYP-678): the JVM flags stop the JVM's own heap/hs_err/core, `LimitCORE=0` also stops an OS/native
 * core (a SIGSEGV in JNI native code — pty4j / sqlite). It is the exact Linux equivalent of the macOS daemon plist
 * `Core=0` (CYP-670), pinned by [CyppieDaemonBootPersistenceTest].
 *
 * ★ Asserts the actual `[Service]` DIRECTIVE line (`LimitCORE=0`), not a substring — the comment prose also mentions
 * "LimitCORE=0", so a `contains` check would be false-green if the directive were removed but the comment left. Both
 * units are declared `test` inputs (CC2) so editing either re-runs this guard. Mutation: remove the `LimitCORE=0`
 * directive from a unit → red.
 */
class HubSystemdCoreDumpTest {

    private val units = listOf(
        "deploy/linux/cyppiehub.service",       // prod (CYP-634)
        "deploy/linux/cyppiehub-test.service",  // the CYP-637 lifecycle-acceptance twin
    )

    @Test
    fun eachHubSystemdUnit_disablesCoreDumps_withLimitCore0() {
        for (u in units) {
            val hasDirective = repoFile(u).readLines().any { it.trim() == "LimitCORE=0" }
            assertTrue(
                hasDirective,
                "$u must set the [Service] directive `LimitCORE=0` (not just mention it in a comment) — the OS core-dump " +
                    "complement to the launcher's in-JVM dump-off flags; without it an OS/native core can spill the hub's " +
                    "decrypted master key to disk (the Linux equivalent of the macOS plist Core=0).",
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
