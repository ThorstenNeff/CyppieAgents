package com.tneff.cyppieagents.comm

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * CYP-883 (OS-C) — the render ≠ authority boundary holds: the channel-mgmt PANEL and pure MODEL are display + honesty
 * only; the actual server round-trip is the INJECTED [ChannelMgmtApi] seam. Neither must open a real HTTP client /
 * dial the endpoints itself (that is `HttpChannelMgmtApi`, the wiring). A reflex that "just POSTs from the panel" —
 * bypassing the seam and the honest error surface — reddens here. (`channelMutationReason` READING a status off
 * [CommHttpException] is allowed — that IS the honest mapping; SENDING is what's forbidden.)
 */
class Cyp883ChannelMgmtDarkBoundaryGuardTest {

    /** HTTP-SENDING tokens the display-only panel + pure model must never reference (the api seam owns the round-trip). */
    private val forbiddenHttpTokens = listOf(
        "HttpClient", "client.post", "client.put", "client.delete", "io.ktor", "baseUrl", "ensureSuccess", ".dial(",
    )

    @Test
    fun channelManagementPanel_neverSpeaksHttpDirectly() {
        assertNoHttp("src/commonMain/kotlin/com/tneff/cyppieagents/comm/ChannelManagementPanel.kt")
    }

    @Test
    fun channelMgmtModel_neverSpeaksHttpDirectly() {
        assertNoHttp("src/commonMain/kotlin/com/tneff/cyppieagents/comm/ChannelMgmtModel.kt")
    }

    private fun assertNoHttp(rel: String) {
        val code = codeLinesOf(rel)
        for (token in forbiddenHttpTokens) {
            assertTrue(
                code.none { it.contains(token) },
                "CYP-883: the display-only channel-mgmt unit ($rel) must NOT speak HTTP directly ('$token') — the " +
                    "mutation round-trip is the injected ChannelMgmtApi seam; the panel owns display + optimistic " +
                    "rollback + honest error only (render ≠ authority). Found a forbidden reference.",
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
