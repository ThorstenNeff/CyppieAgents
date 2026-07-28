package com.tneff.cyppieagents.multihub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-861 (Compose-M4 list-live) — two structural tripwires (comment-stripped source scans):
 *
 *  1. **The DARK boundary holds** — [ControlPlaneHubListSource] fetches the hub LIST and nothing more: no
 *     descriptor→endpoint derivation, no connect, no switch-effect, no observed-trust. A reflex that "just makes it
 *     connect/switch" from the list source reddens here (arming is Compose-M3/§9.3, a separate lane).
 *  2. **The seam is actually flipped LIVE** — `App.kt` wires the real hub list into the CYP-856 `AgentShell` bar via
 *     the env-gate `hubListSourceFor(controlPlane)` (real CP → live; INERT stub → null → dormant). Removing the wiring
 *     reddens (a byte-identical-but-dead mount would otherwise pass unnoticed).
 */
class Cyp861WiringGuardTest {

    /** Connect/arming/observe tokens the display-only LIST source must never reference. */
    private val armingTokens = listOf(
        "switchTo", "HubConnector", "createActiveHubConnection", "connectLocal", "connectRemote",
        "TransportModeResolver", "HubEndpoint", "LocalConnectFeed", "RemoteConnectComponents",
        "buildRemoteHubSession", "hubEndpoint(", "endpointFor", "recordObservation", "displayedTrust", "onSwitch",
    )

    @Test
    fun controlPlaneHubListSource_neverReferences_armingOrEndpointSeam() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/ControlPlaneHubListSource.kt")
        for (token in armingTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-861: the display-only ControlPlaneHubListSource must NOT reference the arming token '$token' — it " +
                    "fetches the hub LIST only; connect/switch/observe are the Compose-M3/§9.3 arming lane. Found a reference.",
            )
        }
    }

    @Test
    fun app_wiresHubListSource_viaEnvGate() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/App.kt")
        assertTrue(
            code.any { it.contains("hubListSource = hubListSourceFor(controlPlane)") },
            "CYP-861: App.kt must wire the AgentShell hub-list bar live via the env-gate `hubListSourceFor(controlPlane)` " +
                "— real CP → live source, INERT stub → null → dormant. The live mount is missing.",
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
