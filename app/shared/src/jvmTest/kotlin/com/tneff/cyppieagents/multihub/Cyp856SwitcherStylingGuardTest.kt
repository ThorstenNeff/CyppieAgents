package com.tneff.cyppieagents.multihub

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-856 Slice-2 — two structural styling tripwires (comment-stripped source scans, deterministic):
 *
 *  1. **reachability (and all switcher chrome) is STRICTLY NEUTRAL** — the load-bearing honesty point. reachability is
 *     a net fact, NOT a severity or a trust verdict: online is NEVER green (green-overload + a false "good/trusted"),
 *     offline is NEVER error-red (offline ≠ "broken/untrusted"). The switcher paints name/reach/lastSeen/active in
 *     `onSurface`/`onSurfaceVariant` ONLY — it must never reach a severity/primary/trust colour. (The error STATE is
 *     the separate `LoadErrorRetry` component; the trust axis is the separate `HubTrustBadge`.) A reflex that tints
 *     online green or offline red reddens here. Mutation: reach `onSurfaceVariant → error` → RED.
 *  2. **placement** — the [HubSwitcherBar] is mounted in `AgentShell` (the in-workspace top-bar over `WindowHost`),
 *     and it is **dormant** (gated on the injected `hubListSource`) so prod chrome stays byte-identical.
 */
class Cyp856SwitcherStylingGuardTest {

    /** Severity / primary / trust colour tokens the neutral switcher chrome must never reference. */
    private val nonNeutralColorTokens = listOf(
        "colorScheme.error", "colorScheme.primary", "colorScheme.tertiary", "colorScheme.secondary",
        "Color.Green", "Color.Red", "severityColor", "hubTrustTone", "Severity.",
    )

    @Test
    fun hubSwitcher_chrome_isStrictlyNeutral_neverSeverityOrTrustColour() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/multihub/HubSwitcher.kt")
        for (token in nonNeutralColorTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-856 §2: the switcher chrome (esp. reachability) must be STRICTLY NEUTRAL — no '$token'. online ≠ " +
                    "green, offline ≠ error-red; a net fact never reads as a trust/health verdict. Found a non-neutral colour.",
            )
        }
    }

    @Test
    fun hubSwitcherBar_isMounted_inAgentShell_dormantOnInjectedSource() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/AgentShell.kt")
        assertTrue(
            code.any { it.contains("HubSwitcherBar(") },
            "CYP-856 §1 placement: AgentShell must MOUNT the HubSwitcherBar (the in-workspace top-bar over WindowHost).",
        )
        assertTrue(
            code.any { it.contains("if (hubListSource != null)") },
            "CYP-856: the HubSwitcherBar mount must be DORMANT — gated on the injected `hubListSource` so prod chrome is " +
                "byte-identical (the real source + activeHubId/onSwitch arming are M3). Found the mount ungated.",
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
