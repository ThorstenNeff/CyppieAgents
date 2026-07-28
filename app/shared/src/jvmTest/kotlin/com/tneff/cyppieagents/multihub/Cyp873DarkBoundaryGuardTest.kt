package com.tneff.cyppieagents.multihub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-873 — the DARK boundary holds at the M4 mount-host. [MultiHubShell] wires the display-only M1–M5 units and
 * drives the ONE-ACTIVE lifecycle through the **injected** [HubConnector] seam — but it must NEVER reach a REAL
 * dial / transport / statusFeed itself (that is the §9.3 arming seam, supplied via the connector at arming). A reflex
 * that "just makes it connect" from the host — swapping the idle connector for a real dialer inline, or reaching the
 * relay/control-plane/session directly — reddens here (like CYP-856/861/865/866).
 *
 * NOTE: `HubConnector` / `createActiveHubConnection` / `switchTo` / `displayedTrust` / `progressionStateFor` are the
 * legitimate DARK M3/M5 units the host MOUNTS — they are NOT forbidden here. Only the real transport is.
 */
class Cyp873DarkBoundaryGuardTest {

    /** Real dial / transport / statusFeed / relay / control-plane tokens the display-only host must never reference. */
    private val armingTokens = listOf(
        "statusFeed", "RelayDialer", "RendezvousRelayDialer", "HttpControlPlaneClient", "ControlPlaneClient",
        "TransportModeResolver", "HubEndpoint", "RemoteHubSession", "RemoteTunnelHubTransport", "webSocket",
        "connectLocal", "connectRemote", "endpointFor", "buildRemoteHubSession", ".dial(",
    )

    @Test
    fun multiHubShell_neverReferences_realDialOrTransport() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/MultiHubShell.kt")
        for (token in armingTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-873: the display-only M4 host (MultiHubShell) must NOT reference the arming/transport token " +
                    "'$token' — the host mounts the display units + drives the ONE-ACTIVE lifecycle via the INJECTED " +
                    "connector; the real dial is the §9.3 arming seam (supplied through that connector). Found a reference.",
            )
        }
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
