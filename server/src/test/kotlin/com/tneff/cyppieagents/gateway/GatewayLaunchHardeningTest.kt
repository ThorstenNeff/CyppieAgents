package com.tneff.cyppieagents.gateway

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CYP-638 S7 — pins the **A2 gateway launch hardening as CONSTRUCTION, not a runbook line** (PO-Assistant flag).
 *
 * The A2 gateway terminates the operator↔hub tunnel, so decrypted cleartext — session tokens, `?token`/`?ticket`, and
 * PTY code-exec bytes — lives in this process's heap. A heap dump (on OOM or on-demand) or a JVM core dump writes that
 * cleartext to DISK, so *"no cleartext on disk"* is FALSE while such a dump can fire. The mitigation must be a **flag in
 * the launch config** (a sentence in a runbook does not hold). This tooth proves two construction facts:
 *
 *  1. the SINGLE-SOURCE argfile `deploy/gateway/gateway.jvmargs` carries the mandated hardening flags; and
 *  2. `server/build.gradle.kts` actually WIRES that argfile into the `gatewayRun` launcher (so the flags reach the
 *     launched process — not an argfile nobody reads).
 *
 * Together they make the flag ride the launcher. (A live `/proc/<pid>/cmdline` check on `:server:gatewayRun` is the
 * real-run complement, reported at the gate.)
 *
 * Mutation-proven: delete `-XX:-HeapDumpOnOutOfMemoryError` from the argfile → (1) reds; delete the `jvmArgs(...
 * gateway.jvmargs ...)` wiring from `gatewayRun` → (2) reds.
 */
class GatewayLaunchHardeningTest {

    /** The flags that MUST ride the gateway launcher — the PO-Assistant cleartext-on-disk mitigations plus the shared
     *  Netty-JFR guard and a bounded heap. */
    private val mandatedArgs = listOf(
        "-XX:-HeapDumpOnOutOfMemoryError", // no .hprof of the cleartext heap on OOM (even if the env tried to enable it)
        "-XX:-CreateCoredumpOnCrash",      // no JVM core image of the cleartext heap on a fatal error
        "-Dio.netty.jfr.enabled=false",    // CYP-206/225 — the gateway runs on Netty too
        "-XX:MaxRAMPercentage=75.0",       // CYP-417 — a knowable, bounded ceiling (fail-closed, not host-dragging)
    )

    @Test
    fun gatewayArgfile_carriesTheMandatedHardeningFlags() {
        val argfile = repoFile("deploy/gateway/gateway.jvmargs")
        val declared = argfile.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (arg in mandatedArgs) {
            assertTrue(
                arg in declared,
                "the gateway launch hardening arg [$arg] is MISSING from ${argfile.path} — without it the A2 cleartext " +
                    "heap can spill to disk. Declared: $declared",
            )
        }
    }

    @Test
    fun buildScript_wiresTheArgfileIntoGatewayRun() {
        val build = repoFile("server/build.gradle.kts").readText()
        // The gatewayRun launcher must READ the single-source argfile — otherwise the flags exist but never ride the
        // process. Coarse-but-honest tripwire: the argfile path + a jvmArgs wiring both appear in the script.
        assertTrue(
            build.contains("deploy/gateway/gateway.jvmargs"),
            "server/build.gradle.kts does not reference the gateway hardening argfile — the flags reach no launcher",
        )
        assertTrue(
            build.contains("gatewayRun") && build.contains("jvmArgs"),
            "server/build.gradle.kts does not wire jvmArgs into gatewayRun — the hardening argfile is read by nobody",
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
