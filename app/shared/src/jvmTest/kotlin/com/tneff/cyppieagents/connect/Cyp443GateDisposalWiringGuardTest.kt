package com.tneff.cyppieagents.connect

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-443 Slice 3 — the **disposal-reachability guard** (the actual fix-proof). The connect VM is held by a plain
 * `remember` in [RemoteHubConnectGate], NOT a `ViewModelStore`, so `ViewModel.onCleared` never fires on composition
 * exit; the ONLY thing that routes a leave (logout / auth-subtree teardown) to the teardown chokepoint is the
 * gate's `DisposableEffect { onDispose { viewModel.dispose() } }`. If that wiring is dropped, the crown-jewel
 * decrypted key + live tunnels leak on logout — silently, since nothing else reaches the chokepoint.
 *
 * This guard pins **reachability, not mere existence** (the Slice-2 lesson: a lifecycle tooth must prove the
 * trigger FIRES, not just that teardown-on-trigger computes). It asserts the gate source carries the composed
 * `onDispose { … dispose() }` wiring on ONE line — so **neutralizing the `onDispose` call reddens this tooth**.
 * A source scan (comments stripped) is used deliberately: a `runComposeUiTest` mount/unmount hangs headless in
 * this env, and the disposal callback firing on composition-exit is a framework guarantee once the wiring is present.
 */
class Cyp443GateDisposalWiringGuardTest {

    @Test
    fun remoteHubConnectGate_routesDisposalToTeardownChokepoint() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/connect/RemoteHubConnectGate.kt")

        assertTrue(
            code.any { it.contains("DisposableEffect") },
            "CYP-443 S3: RemoteHubConnectGate must register a DisposableEffect so composition-exit is observed " +
                "(the VM is a plain remember → onCleared never fires) — else logout leaks keyHold/tunnels.",
        )
        // Reachability: the disposal callback must CALL the teardown. Neutralizing `onDispose { … dispose() }` drops
        // this single-line wiring → this assertion reddens (the leak the guard exists to catch).
        assertTrue(
            code.any { line -> line.contains("onDispose") && line.contains("dispose()") },
            "CYP-443 S3: the gate's onDispose must call viewModel.dispose() (route the leave to closeActiveComponents). " +
                "Without it, logout drops the VM with the decrypted device key + live Noise tunnels un-torn-down (H-1 leak).",
        )
    }

    /** Production source lines with comment/blank lines stripped, so a KDoc mention is not a false pass. */
    private fun codeLinesOf(rel: String): List<String> =
        locateSource(rel).readLines().filterNot { raw ->
            val t = raw.trimStart()
            t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
        }

    private fun locateSource(rel: String): File {
        var cur: File? = File(".").absoluteFile
        while (cur != null) {
            File(cur, rel).let { if (it.isFile) return it }
            File(cur, "app/shared/$rel").let { if (it.isFile) return it }
            cur = cur.parentFile
        }
        error("could not locate $rel")
    }
}
