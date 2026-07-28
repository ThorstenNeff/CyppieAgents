package com.tneff.cyppieagents.multihub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-866 — the DARK boundary holds: the Compose-M5 progression units are DISPLAY-only. They read a conn-state enum
 * and render a Zone-2 step; they must NEVER wire a real dial / transport / statusFeed (that is the §9.3 arming seam,
 * driven by the injected machine). A reflex that "just connects" from the progression reddens here (like CYP-856/861/865).
 */
class Cyp866DarkBoundaryGuardTest {

    /** Real dial / transport / statusFeed / CP tokens the display-only M5 units must never reference. */
    private val armingTokens = listOf(
        "statusFeed", "RelayDialer", "HttpControlPlaneClient", "ControlPlaneClient", "TransportModeResolver",
        "HubEndpoint", "RemoteHubSession", "webSocket", "connectLocal", "connectRemote", "endpointFor",
        "RemoteTunnelHubTransport", "buildRemoteHubSession", ".dial(", "createActiveHubConnection", "HubConnector",
    )

    @Test
    fun connectProgression_neverReferences_realDialOrTransport() {
        assertNoArming("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/ConnectProgression.kt")
    }

    @Test
    fun connectProgressionChrome_neverReferences_realDialOrTransport() {
        assertNoArming("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/ConnectProgressionChrome.kt")
    }

    private fun assertNoArming(rel: String) {
        val code = codeLinesOf(rel)
        for (token in armingTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-866: the display-only M5 unit ($rel) must NOT reference the arming/transport token '$token' — the " +
                    "progression renders a STATE; the real dial is the §9.3 arming seam (injected machine). Found a reference.",
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
