package com.tneff.cyppieagents.firstrun

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-629 (same reachability discipline as CYP-443 Slice 3) — the **disposal-reachability guard**. [FirstRunGate]
 * holds its VM via a plain `remember` (NOT a `ViewModelStore`), so `ViewModel.onCleared` never fires on composition
 * exit; the ONLY thing that cancels the §7.3 clone poll on a leave (logout / auth-subtree teardown) is the gate's
 * `DisposableEffect { onDispose { viewModel.dispose() } }`. If that wiring is dropped, the poll re-fetches forever.
 *
 * This pins **reachability, not mere existence** — it asserts the gate source carries the composed
 * `onDispose { … dispose() }` wiring on ONE line, so **neutralizing `onDispose→dispose` reddens this tooth**. A
 * source scan (comments stripped) is deliberate: a `runComposeUiTest` mount/unmount hangs headless in this env,
 * and the disposal callback firing on composition-exit is a framework guarantee once the wiring is present.
 */
class Cyp629GateDisposalWiringGuardTest {

    @Test
    fun firstRunGate_routesDisposalToViewModelDispose() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/firstrun/FirstRunGate.kt")

        assertTrue(
            code.any { it.contains("DisposableEffect") },
            "CYP-629: FirstRunGate must register a DisposableEffect so composition-exit is observed (the VM is a " +
                "plain remember → onCleared never fires) — else the §7.3 clone poll re-fetches forever after a leave.",
        )
        assertTrue(
            code.any { line -> line.contains("onDispose") && line.contains("dispose()") },
            "CYP-629: the gate's onDispose must call viewModel.dispose() (cancel the poll loop). Without it, a logout " +
                "drops the gate with the clone poll still re-fetching (the forever-poll leak this guard exists to catch).",
        )
    }

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
