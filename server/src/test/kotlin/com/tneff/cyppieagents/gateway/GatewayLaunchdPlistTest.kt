package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-667 S7 — pins the macOS **launchd** gateway job as CONSTRUCTION (the target host is macOS: no systemd, no
 * /proc). Two facts the deployed launcher must carry, proven against the committed artifacts:
 *
 *  1. the wrapper [deploy/launchd/gateway-run.sh] **single-sources** the JVM hardening flags from
 *     `deploy/gateway/gateway.jvmargs` and **hand-copies no `-XX` flag** — so the real launcher cannot drift from the
 *     tested source (the CYP-623 lesson; the same "flags-existed-but-the-process-never-got-them" class as the
 *     `gatewayRun` finding, one level up). Delete a flag from the argfile → the launched process loses it too, and
 *     [GatewayLaunchHardeningTest] reds on the argfile.
 *  2. the plist [deploy/launchd/com.cyppie.gateway.plist] sets `Core = 0` in **both** `SoftResourceLimits` AND
 *     `HardResourceLimits` — the macOS equivalent of systemd `LimitCORE=0`, and stronger than hub/relay which set
 *     NEITHER (inheriting soft 0 / hard unlimited, so a process can raise its own soft limit and dump the cleartext
 *     heap). Both limits at 0 ⇒ no process can raise a core limit ⇒ no cleartext core to disk.
 *
 * Mutation-proven: hand-copy a `-XX` flag into the wrapper → (1) reds; change either Core limit to non-zero (or drop
 * it) → (2) reds.
 *
 * (The LaunchAgent-vs-LaunchDaemon load-context posture is an open Auftraggeber decision; it does not affect either
 * fact above, so this tooth is posture-independent.)
 */
class GatewayLaunchdPlistTest {

    @Test
    fun wrapper_singleSourcesTheArgfile_andHandCopiesNoHardeningFlag() {
        val wrapper = repoFile("deploy/launchd/gateway-run.sh").readText()
        assertTrue(
            wrapper.contains("gateway/gateway.jvmargs"),
            "the launchd wrapper must READ the single-source argfile deploy/gateway/gateway.jvmargs — otherwise the " +
                "launched process gets no hardening flags (the gatewayRun 'zero hardening' class, one level up)",
        )
        // ★ anti-drift: the flags come FROM the argfile, never inline here. A hand-copied -XX flag is exactly the
        //   second-arg-list that drifts against the tested source (CYP-623).
        assertFalse(
            Regex("""-XX:[-+:\w.=]+""").containsMatchIn(wrapper),
            "the launchd wrapper must NOT hand-copy any -XX flag — the hardening flags must come from the argfile only",
        )
    }

    @Test
    fun plist_setsCoreLimitZero_inBothSoftAndHard_andRunsTheWrapper() {
        val plist = repoFile("deploy/launchd/com.cyppie.gateway.plist").readText()
        assertTrue(plist.contains("gateway-run.sh"), "the plist ProgramArguments must run the gateway-run.sh wrapper")
        assertTrue(plist.contains("<key>RunAtLoad</key>") && plist.contains("<key>KeepAlive</key>"),
            "the plist must RunAtLoad + KeepAlive (the measured hub/relay pattern)")
        // ★ Core=0 in BOTH resource-limit blocks — hub/relay set NEITHER, so the gateway is the one that closes the
        //   OS core-dump vector. Extract each block and assert its Core integer is 0.
        for (limit in listOf("SoftResourceLimits", "HardResourceLimits")) {
            val key = "<key>$limit</key>"
            assertTrue(plist.contains(key), "the plist must set $limit")
            val block = plist.substringAfter(key).substringBefore("</dict>")
            assertTrue(
                Regex("""<key>Core</key>\s*<integer>0</integer>""").containsMatchIn(block),
                "$limit must set Core=0 (no cleartext core dump to disk) — found block: ${block.trim()}",
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
