package com.tneff.cyppieagents.comm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-884 (OS-D) — the render ≠ authority boundary holds: recipient addressing resolves a spoke CLIENT-SIDE from the
 * (ACL-filtered) visible channels and reuses the existing channel-select path. It must NEVER fabricate a channel (an
 * unreachable target is honestly unreachable — a new direct link is OS-C channel-creation) and never speak HTTP /
 * create a channel itself. A reflex that "just makes a channel to DM into" — the exact fabrication this forbids —
 * reddens here.
 */
class Cyp884AgentAddressingGuardTest {

    /** Channel-creation + HTTP tokens the display-only picker + pure resolver must never reference (fabricating a DM
     *  route = reaching the OS-C create path). NB: not the bare `Channel(` ctor — that substring-matches the
     *  `resolveAgentChannel(` name; the behavioral pure+render teeth already prove unreachable never fabricates. */
    private val forbiddenTokens = listOf(
        "HttpClient", "io.ktor", "client.post", "client.put", "client.delete",
        "CreateChannelRequest", "ChannelMgmtApi", "HttpChannelMgmtApi", ".create(",
    )

    @Test
    fun agentAddressing_neverFabricatesAChannel_norSpeaksHttp() {
        assertNoFabrication("src/commonMain/kotlin/com/tneff/cyppieagents/comm/AgentAddressing.kt")
    }

    @Test
    fun agentAddressPicker_neverFabricatesAChannel_norSpeaksHttp() {
        assertNoFabrication("src/commonMain/kotlin/com/tneff/cyppieagents/comm/AgentAddressPicker.kt")
    }

    private fun assertNoFabrication(rel: String) {
        val code = codeLinesOf(rel)
        for (token in forbiddenTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-884: the display-only addressing unit ($rel) must NOT fabricate a channel or speak HTTP ('$token') " +
                    "— an unreachable target is honestly unreachable (a new direct link is OS-C channel-creation); the " +
                    "picker resolves a spoke client-side + reuses the channel-select path. Found a forbidden reference.",
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
