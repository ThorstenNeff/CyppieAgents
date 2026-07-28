package com.tneff.cyppieagents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-885 (OS-C mount) — structural tripwire (comment-stripped source scan): the channel-mgmt window is actually
 * WIRED in `AgentShell` — (1) SEEDED in the operator-only window set and (2) DISPATCHED to the CYP-883
 * `ChannelManagementPanel` in `windowContent`. Removing either half of the wiring (a byte-identical-but-dead const,
 * or a seeded window with no content) reddens here — the same "wired live, not just declared" guard as CYP-861.
 */
class Cyp885WindowMountWiringGuardTest {

    @Test
    fun agentShell_seedsAndDispatches_theChannelMgmtWindow() {
        val code = codeLinesOf("src/commonMain/kotlin/com/tneff/cyppieagents/AgentShell.kt")
        // (1) SEEDED — the operator window set adds the channel-mgmt window (id → title).
        assertTrue(
            code.any { it.contains("add(CHANNEL_MGMT_WINDOW_ID to channelMgmtTitle)") },
            "CYP-885: AgentShell must SEED the channel-mgmt window (add(CHANNEL_MGMT_WINDOW_ID to channelMgmtTitle)) " +
                "in the operator window set — the window is missing from the manager.",
        )
        // (2) DISPATCHED — windowContent maps that id to the CYP-883 ChannelManagementPanel.
        assertTrue(
            code.any { it.contains("CHANNEL_MGMT_WINDOW_ID ->") } && code.any { it.contains("ChannelManagementPanel(") },
            "CYP-885: windowContent must DISPATCH CHANNEL_MGMT_WINDOW_ID → ChannelManagementPanel — a seeded window " +
                "with no content is a dead mount.",
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
