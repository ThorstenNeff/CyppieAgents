package com.tneff.cyppieagents.multihub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-856 — two structural tripwires on the display-only hub switcher, comment-stripped source scans (deterministic,
 * no render):
 *
 *  1. **NO ARMING (M3/M4 stays out).** The switcher + holder are DISPLAY-only: they list hubs and invoke an injected
 *     `onSwitch` seam, but must NEVER derive a connect endpoint from a descriptor or reach the connect/transport path.
 *     A later reflex — wiring the switcher straight into `selectHub`/`connect*`/the endpoint resolver to "just make it
 *     connect" — reddens here instead of silently crossing the arming seam.
 *  2. **axis-c (issuerTrust) + tier ABSENT.** The four switcher axes are name / reachability / hub-key-trust(UNKNOWN)
 *     / freshness. axis-c (`issuerTrust`) is a Zone-2 connect verdict (M4), never a switcher badge; tier is the
 *     active-header, not here. Leaking either field into the switcher reddens (the 4-axis-clean contract, ruling
 *     7dda489f). Mirrors the CYP-443 trust-axis-separation guard.
 */
class Cyp856HubSwitcherSeamGuardTest {

    /** Connect/arming/endpoint type + call tokens the display-only switcher & holder must never reference. */
    private val armingTokens = listOf(
        "selectHub", "connectLocal", "connectRemote", "TransportModeResolver", "HubEndpoint",
        "LocalConnectFeed", "RemoteConnectComponents", "buildRemoteHubSession", "hubEndpoint(",
        "ControlPlaneClient", "RelayDialer",
    )

    /** axis-c + tier field/type tokens that must never appear in the switcher (kept to the 4 clean axes). */
    private val axisCAndTierTokens = listOf("issuerTrust", "IssuerTrust", "HubTier", ".tier")

    @Test
    fun hubSwitcher_neverReferences_armingOrEndpointSeam() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/HubSwitcher.kt")
        for (token in armingTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-856: the display-only HubSwitcher must NOT reference the arming/endpoint token '$token' — " +
                    "descriptor→endpoint derivation + connect are the M3/M4 arming seam, reached only via the injected " +
                    "onSwitch. Found a forbidden reference.",
            )
        }
    }

    @Test
    fun hubListHolder_neverReferences_armingOrEndpointSeam() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/HubListHolder.kt")
        // The holder consumes an injected HubListSource; it must not reach the CP client / connect / endpoint path
        // directly (that real-source wiring is the arming seam). `ControlPlaneClient` is intentionally NOT referenced.
        for (token in armingTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-856: the display-only HubListHolder must NOT reference the arming/endpoint token '$token' — it " +
                    "consumes an injected stub source; the real CP-backed source is the arming seam. Found a forbidden reference.",
            )
        }
    }

    @Test
    fun hubSwitcher_excludes_axisC_issuerTrust_andTier() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/HubSwitcher.kt")
        for (token in axisCAndTierTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-856 (4-axis-clean, ruling 7dda489f): the switcher must NOT reference axis-c/tier token '$token' — " +
                    "issuerTrust is a Zone-2 connect verdict (M4), never a switcher badge; tier belongs to the active-header. " +
                    "Found a forbidden reference.",
            )
        }
    }

    /** Production source lines with comment/blank lines stripped, so a KDoc mention in prose is not a false trip. */
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
