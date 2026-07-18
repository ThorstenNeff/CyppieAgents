package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-667 S7 — pins the macOS **LaunchDaemon** gateway job as CONSTRUCTION (target host = macOS: no systemd, no
 * /proc). Proven against the committed artifacts `deploy/launchd/com.cyppie.gateway.plist` + `.../gateway-run.sh`:
 *
 *  1. ★ **the LAUNCHER delivers the mandated hardening flags** — the plist runs the wrapper, the wrapper reads the
 *     single-source `deploy/gateway/gateway.jvmargs`, so whatever the argfile carries reaches the JVM. Deleting a flag
 *     from the argfile reds THIS plist/launcher assert — not only [GatewayLaunchHardeningTest]'s argfile assert. This
 *     is the exact "test the source, deploy the launcher" crack found at `gatewayRun`, one level up: the tested source
 *     and the deployed launcher must be provably the same chain.
 *  2. the plist sets `Core = 0` in **both** `SoftResourceLimits` AND `HardResourceLimits` (the macOS `LimitCORE=0`),
 *     stronger than hub/relay which set NEITHER.
 *  3. **custody (ratified option (a), ported):** the daemon runs as a dedicated NON-ROOT user, and the wrapper sources
 *     its env from a 0600 file OUTSIDE any user home — the macOS mirror of `User=cyppie` + `EnvironmentFile=/etc/…`.
 *  4. the wrapper hand-copies NO `-XX` flag (anti-drift; the flags come from the argfile only).
 *
 * Mutation-proven: delete a flag from the argfile → (1) reds; Core!=0 in the plist → (2) reds; UserName=root → (3)
 * reds; hand-copy a `-XX` flag into the wrapper → (4) reds.
 */
class GatewayLaunchdPlistTest {

    /** The launch-hardening flags whose delivery to the gateway JVM is security-load-bearing (cleartext heap → disk).
     *  The FULL argfile contents are pinned by [GatewayLaunchHardeningTest]; here we prove the LAUNCHER delivers them. */
    private val mandated = listOf("-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash")

    @Test
    fun plist_launcherDeliversTheMandatedHardeningFlags_viaTheWrapperArgfileChain() {
        val plist = repoFile("deploy/launchd/com.cyppie.gateway.plist").readText()
        val wrapper = repoFile("deploy/launchd/gateway-run.sh").readText()
        val delivered = repoFile("deploy/gateway/gateway.jvmargs").readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        // the chain must be intact: plist → wrapper → argfile.
        assertTrue(plist.contains("gateway-run.sh"), "the plist ProgramArguments must run the gateway-run.sh wrapper")
        assertTrue(wrapper.contains("gateway/gateway.jvmargs"), "the wrapper must READ the single-source argfile")
        // ★ and the argfile the chain reads must carry the mandated flags — delete one and this launcher assert reds.
        for (flag in mandated) {
            assertTrue(
                flag in delivered,
                "the launchd launcher does not deliver [$flag]: the plist runs the wrapper, the wrapper reads the " +
                    "argfile, and the flag is ABSENT from it → the deployed process would launch without it",
            )
        }
    }

    @Test
    fun plist_setsCoreLimitZero_inBothSoftAndHard() {
        val plist = repoFile("deploy/launchd/com.cyppie.gateway.plist").readText()
        assertTrue(plist.contains("<key>RunAtLoad</key>") && plist.contains("<key>KeepAlive</key>"),
            "the plist must RunAtLoad + KeepAlive (boot-persistent, Caddy pattern)")
        for (limit in listOf("SoftResourceLimits", "HardResourceLimits")) {
            assertTrue(plist.contains("<key>$limit</key>"), "the plist must set $limit")
            val block = plist.substringAfter("<key>$limit</key>").substringBefore("</dict>")
            assertTrue(
                Regex("""<key>Core</key>\s*<integer>0</integer>""").containsMatchIn(block),
                "$limit must set Core=0 (no cleartext core dump to disk) — found: ${block.trim()}",
            )
        }
    }

    @Test
    fun plist_runsAsDedicatedNonRootUser_custodyOptionA() {
        val plist = repoFile("deploy/launchd/com.cyppie.gateway.plist").readText()
        val user = Regex("""<key>UserName</key>\s*<string>([^<]+)</string>""").find(plist)?.groupValues?.get(1)?.trim()
        assertNotNull(user, "the gateway LaunchDaemon must declare a UserName (custody option a: dedicated service user)")
        assertTrue(
            user.isNotBlank() && user != "root",
            "the gateway LaunchDaemon must run as a dedicated NON-ROOT user (custody option a); got UserName='$user'",
        )
    }

    @Test
    fun wrapper_singleSourcesArgfile_handCopiesNoFlag_andSourcesEnvOutsideUserHome() {
        val wrapper = repoFile("deploy/launchd/gateway-run.sh").readText()
        // anti-drift: the flags come FROM the argfile, never inline here (CYP-623).
        assertFalse(
            Regex("""-XX:[-+:\w.=]+""").containsMatchIn(wrapper),
            "the wrapper must NOT hand-copy any -XX flag — the hardening flags must come from the argfile only",
        )
        // custody: the default env file is an ABSOLUTE path OUTSIDE any user home (the 0600 out-of-home mirror of
        // EnvironmentFile=/etc/cyppiehub/hub.env). A LaunchDaemon must not depend on a console-user's home .env.
        val envDefault = Regex("""CYPPIE_GATEWAY_ENV_FILE:-([^}]+)""").find(wrapper)?.groupValues?.get(1)?.trim()
        assertNotNull(envDefault, "the wrapper must define a default env-file path")
        assertTrue(
            envDefault.startsWith("/") && !envDefault.startsWith("/Users") && "\$HOME" !in envDefault && !envDefault.startsWith("~"),
            "the wrapper's default env file must be an absolute path OUTSIDE any user home (custody option a); got: $envDefault",
        )
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
