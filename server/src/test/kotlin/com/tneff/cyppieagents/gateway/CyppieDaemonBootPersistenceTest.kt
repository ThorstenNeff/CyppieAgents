package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-670 — pins the Hub + Relay macOS LaunchDaemons to **boot-persistent** (the flip precondition: the whole chain
 * must come up after a HEADLESS reboot with NO console login). The bar is "boot-persistent", NOT "plist exists":
 *
 *  - **LaunchDaemon domain, not a User-LaunchAgent** — a dedicated NON-ROOT `UserName` (a system daemon), and NO
 *    console-session dependency: the plist references no user home (`/Users`) and no `gui/` per-user domain.
 *  - **RunAtLoad + KeepAlive** — starts at load (boot) and is kept alive.
 *  - **Custody (option (a), ported):** the wrapper sources its env from a 0600 file OUTSIDE any user home (a daemon
 *    outside the console session cannot depend on a `customer`-home .env), and single-sources its JVM args from the
 *    one argfile (no hand-copied `-XX` flag; CYP-623 drift lesson).
 *
 * The real headless cold-boot is deploy's execution test; this pins the config properties that make it possible.
 * (Core=0 / heap-dump-off is D2-pending — not asserted here.) Mutation-proven: UserName=root, or a `/Users` path in
 * the plist, or the wrapper hand-copying a `-XX` flag / reading no argfile → the relevant assert reds.
 */
class CyppieDaemonBootPersistenceTest {

    private data class Daemon(val name: String, val plist: String, val wrapper: String, val argfile: String, val mainClass: String)

    private val daemons = listOf(
        Daemon("hub", "deploy/launchd/com.cyppie.hub.plist", "deploy/launchd/hub-run.sh", "deploy/hub/hub.jvmargs", "ApplicationKt"),
        Daemon("relay", "deploy/launchd/com.cyppie.relay.plist", "deploy/launchd/relay-run.sh", "deploy/relay/relay.jvmargs", "RelayServerKt"),
    )

    /** CYP-670 D2 — the cleartext-on-disk hardening flags each daemon's launcher must DELIVER (hub holds the master key
     *  in RAM; relay holds pre-encryption handshake material). Uniform with the gateway. */
    private val mandatedFlags = listOf("-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash")

    @Test
    fun eachDaemon_isBootPersistent_notJustPresent() {
        for (d in daemons) {
            val plist = repoFile(d.plist).readText()
            // RunAtLoad + KeepAlive → starts at boot and stays up.
            assertTrue(plist.contains("<key>RunAtLoad</key>") && plist.contains("<key>KeepAlive</key>"),
                "[${d.name}] plist must RunAtLoad + KeepAlive (boot-persistent)")
            // LaunchDaemon, not a User-LaunchAgent: a dedicated NON-ROOT user, and NO console-session dependency.
            val user = Regex("""<key>UserName</key>\s*<string>([^<]+)</string>""").find(plist)?.groupValues?.get(1)?.trim()
            assertNotNull(user, "[${d.name}] plist must declare a UserName (dedicated service user)")
            assertTrue(user.isNotBlank() && user != "root", "[${d.name}] must run as a dedicated NON-ROOT user; got '$user'")
            assertFalse(plist.contains("/Users"), "[${d.name}] plist must not reference a user home (/Users) — boot must not need a login")
            assertFalse(plist.contains("gui/"), "[${d.name}] plist must not use a per-user gui/ domain — that needs a console session")
            // ProgramArguments runs the wrapper.
            assertTrue(plist.contains("${d.name}-run.sh"), "[${d.name}] plist must run its ${d.name}-run.sh wrapper")
        }
    }

    @Test
    fun eachWrapper_sourcesEnvOutOfHome_andSingleSourcesArgs() {
        for (d in daemons) {
            val wrapper = repoFile(d.wrapper).readText()
            // custody: default env file is an ABSOLUTE path OUTSIDE any user home (a daemon can't read a console .env).
            val envDefault = Regex("""_ENV_FILE:-([^}]+)""").find(wrapper)?.groupValues?.get(1)?.trim()
            assertNotNull(envDefault, "[${d.name}] wrapper must define a default env-file path")
            assertTrue(
                envDefault.startsWith("/") && !envDefault.startsWith("/Users") && "\$HOME" !in envDefault && !envDefault.startsWith("~"),
                "[${d.name}] wrapper env file must be absolute + outside any user home; got: $envDefault",
            )
            // single-source: reads its argfile, hand-copies no -XX flag.
            assertTrue(wrapper.contains(".jvmargs"), "[${d.name}] wrapper must READ its single-source .jvmargs argfile")
            assertFalse(Regex("""-XX:[-+:\w.=]+""").containsMatchIn(wrapper),
                "[${d.name}] wrapper must NOT hand-copy any -XX flag — args come from the argfile only")
            assertTrue(wrapper.contains(d.mainClass), "[${d.name}] wrapper must exec ${d.mainClass}")
        }
    }

    @Test
    fun eachDaemon_plistLauncherDeliversTheHardeningFlags_notJustTheArgfile() {
        // ★ the tooth pins the PLIST, not just the argfile: launchd runs the PLIST → the wrapper → the argfile. Assert
        //   the chain is intact AND the argfile it reads carries the mandated flags — so deleting a flag from the
        //   argfile reds THIS launcher assert (a tooth that only checked the argfile is false-green if the plist does
        //   not pull it). Same construction as GatewayLaunchdPlistTest.
        for (d in daemons) {
            val plist = repoFile(d.plist).readText()
            val wrapper = repoFile(d.wrapper).readText()
            val delivered = repoFile(d.argfile).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            assertTrue(plist.contains("${d.name}-run.sh"), "[${d.name}] plist must run the wrapper")
            assertTrue(wrapper.contains(".jvmargs"), "[${d.name}] wrapper must read the single-source argfile")
            for (flag in mandatedFlags) {
                assertTrue(flag in delivered, "[${d.name}] launcher does not deliver [$flag] — plist→wrapper→argfile chain, flag absent from the argfile")
            }
        }
    }

    @Test
    fun eachDaemon_plistSetsCoreLimitZero_inBothSoftAndHard() {
        // ★ strip Core=0 from the PLIST → this reds. The OS complement to the argfile's -XX:-CreateCoredumpOnCrash.
        for (d in daemons) {
            val plist = repoFile(d.plist).readText()
            for (limit in listOf("SoftResourceLimits", "HardResourceLimits")) {
                assertTrue(plist.contains("<key>$limit</key>"), "[${d.name}] plist must set $limit")
                val block = plist.substringAfter("<key>$limit</key>").substringBefore("</dict>")
                assertTrue(
                    Regex("""<key>Core</key>\s*<integer>0</integer>""").containsMatchIn(block),
                    "[${d.name}] $limit must set Core=0 (no cleartext/secret core dump to disk)",
                )
            }
        }
    }

    @Test
    fun relayWrapper_bindsLoopback_onThisCaddyFrontedHost() {
        // CASE 2 (measured: Caddy fronts /relay → 127.0.0.1:8788): the relay must bind loopback, not the code default
        // 0.0.0.0. The wrapper exports it so a missing env can't leave it non-loopback.
        val wrapper = repoFile("deploy/launchd/relay-run.sh").readText()
        assertTrue(
            Regex("""export\s+CYPPIE_RELAY_HOST=.*127\.0\.0\.1""").containsMatchIn(wrapper),
            "the relay wrapper must export CYPPIE_RELAY_HOST=127.0.0.1 (loopback, Caddy-fronted case 2)",
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
