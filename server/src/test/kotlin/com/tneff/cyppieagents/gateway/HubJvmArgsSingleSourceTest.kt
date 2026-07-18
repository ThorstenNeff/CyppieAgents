package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-678 — pins that the hub's JVM launch args are **single-sourced** from `deploy/hub/hub.jvmargs`, closing the
 * pre-existing CYP-623 drift where the args lived in TWO hand-copied lists in `server/build.gradle.kts`:
 *   • `applicationDefaultJvmArgs` — the Gradle `run` / `installDist` launcher (generated start scripts + `:server:run`);
 *   • `launcherArgs` — the `.deb` jpackage `--java-options` (the CYP-626 installer).
 *
 * ★ False-green-safe, BOTH sides (the CYP-670 `plistLauncherDelivers` pattern): each side gets its OWN assert that reads
 * BOTH the wiring (`build.gradle.kts` derives that side's args FROM `hub.jvmargs`, not a literal list) AND the source
 * content (`hub.jvmargs` carries the mandated flags). So:
 *   • delete a flag from `hub.jvmargs` → BOTH per-side content checks red (both sides read the source);
 *   • hard-code EITHER side back to a literal list → THAT side's wiring check reds.
 * A tooth that only checked the argfile content — or only one side — would be false-green if one side were still
 * hand-copied, which is exactly the drift this closes.
 *
 * Build-config assertion by design (PO-ratified): it pins the WIRING (which is what CYP-678 consolidates), NOT a real
 * jpackage/installDist run — running the real `.deb` build to prove the flags ride the artifact is too slow/heavy for
 * the gate, and the wiring IS the single-source property. `hub.jvmargs` + `server/build.gradle.kts` are declared `test`
 * inputs, so editing either re-runs this guard (CC2 stale-green fix).
 */
class HubJvmArgsSingleSourceTest {

    /** The security-load-bearing flags both hub launchers must deliver (from the ONE source): the CYP-206/623 Netty-JFR
     *  guard + the CYP-670 D2 cleartext-on-disk hardening (the hub holds the master key in RAM). */
    private val mandated = listOf(
        "-Dio.netty.jfr.enabled=false",
        "-XX:-HeapDumpOnOutOfMemoryError",
        "-XX:-CreateCoredumpOnCrash",
    )

    private fun hubArgfileContent(): List<String> =
        repoFile("deploy/hub/hub.jvmargs").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun gradleRunInstallDist_derivesArgsFromHubJvmargs() {
        val build = repoFile("server/build.gradle.kts").readText()
        // WIRING: applicationDefaultJvmArgs derives from the hub.jvmargs single source (via `hubJvmArgs`), not a literal.
        assertTrue(
            build.contains("applicationDefaultJvmArgs = hubJvmArgs"),
            "applicationDefaultJvmArgs must derive from the hub.jvmargs single source (hubJvmArgs), not a hand-copied list",
        )
        assertTrue(
            build.contains("val hubJvmArgs") && build.contains("\"deploy/hub/hub.jvmargs\""),
            "build.gradle must read deploy/hub/hub.jvmargs into the hubJvmArgs single source",
        )
        // CONTENT (delivered): the source carries the mandated flags → the Gradle launcher delivers them.
        val delivered = hubArgfileContent()
        for (f in mandated) assertTrue(f in delivered, "the Gradle run/installDist launcher does not deliver [$f] — absent from deploy/hub/hub.jvmargs")
    }

    @Test
    fun debJpackageLauncher_derivesArgsFromHubJvmargs() {
        val build = repoFile("server/build.gradle.kts").readText()
        // WIRING: the .deb launcherArgs (jpackage --java-options) derives from the SAME hubJvmArgs source, not a literal.
        assertTrue(
            build.contains("val launcherArgs = hubJvmArgs"),
            "the .deb launcherArgs (jpackage --java-options) must derive from the hub.jvmargs single source (hubJvmArgs), not a hand-copied list",
        )
        // CONTENT (delivered): the same source carries the mandated flags → the .deb launcher delivers them.
        val delivered = hubArgfileContent()
        for (f in mandated) assertTrue(f in delivered, "the .deb jpackage --java-options do not deliver [$f] — absent from deploy/hub/hub.jvmargs")
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
