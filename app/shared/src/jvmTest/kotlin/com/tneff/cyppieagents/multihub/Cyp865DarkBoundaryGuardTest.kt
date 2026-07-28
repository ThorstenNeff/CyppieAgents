package com.tneff.cyppieagents.multihub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-865 — the DARK boundary holds: the Compose-M3 units own only the ONE-ACTIVE **lifecycle** + trust **state**;
 * the real dial (socket + statusFeed over the wire) is the ARMING seam (§9.3), reached only through the INJECTED
 * [HubConnector]. Neither [createActiveHubConnection] nor [displayedTrust] may reference a real transport / dial /
 * statusFeed / CP client. A reflex that "just makes it connect" from the conn-model reddens here (like CYP-856/861).
 */
class Cyp865DarkBoundaryGuardTest {

    /** Real dial / transport / statusFeed / CP tokens the display-only M3 units must never reference. */
    private val armingTokens = listOf(
        "statusFeed", "RelayDialer", "HttpControlPlaneClient", "ControlPlaneClient", "TransportModeResolver",
        "HubEndpoint", "RemoteHubSession", "webSocket", "connectLocal", "connectRemote", "endpointFor",
        "RemoteTunnelHubTransport", "buildRemoteHubSession", ".dial(",
    )

    @Test
    fun activeHubConnection_neverReferences_realDialOrTransport() {
        assertNoArming("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/ActiveHubConnection.kt")
    }

    @Test
    fun hubTrustProvenance_neverReferences_realDialOrTransport() {
        assertNoArming("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/HubTrustProvenance.kt")
    }

    private fun assertNoArming(rel: String) {
        val code = codeLinesOf(rel)
        for (token in armingTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-865: the display-only M3 unit ($rel) must NOT reference the arming/transport token '$token' — the " +
                    "real dial is the §9.3 arming seam, reached only via the INJECTED HubConnector. Found a reference.",
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
